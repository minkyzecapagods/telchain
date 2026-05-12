package telchain.server;

import telchain.blockchain.Block;
import telchain.blockchain.Blockchain;
import telchain.blockchain.Transaction;

import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * Responsável por gerenciar a comunicação com um único cliente.
 * Implementa o parser do protocolo TelChain e despacha os comandos.
 *
 * Cada instância executa em sua própria thread (fornecida pelo servidor),
 * permitindo tratamento concorrente de múltiplos clientes.
 */
public class ClientHandler implements Runnable {

    private final Socket socket;
    private final String sessionId;
    private final TelChainServer server;

    // Canais de comunicação (inicializados no run)
    private PrintWriter out;
    private BufferedReader in;

    // Estado da autenticação
    private boolean authenticated = false;
    private String deviceId;
    private String role; // SENSOR ou DASHBOARD

    // Conjunto de tipos de evento que este dashboard deseja receber
    private final Set<String> subscriptions = new HashSet<>();

    /**
     * Credenciais hard‑coded para demonstração.
     *
     * Este mapa é uma cópia do existente em TelChainServer.
     * Idealmente, o handler deveria usar o mapa centralizado do servidor,
     * mas isso é suficiente para validar o conceito.
     */
    private static final Map<String, String[]> CREDENTIALS = new HashMap<>();
    static {
        CREDENTIALS.put("sensor-01",  new String[]{"s3cr3t",   "SENSOR"});
        CREDENTIALS.put("sensor-02",  new String[]{"s3cr3t2",  "SENSOR"});
        CREDENTIALS.put("sensor-03",  new String[]{"s3cr3t3",  "SENSOR"});
        CREDENTIALS.put("dash-01",    new String[]{"d4shp4ss",  "DASHBOARD"});
        CREDENTIALS.put("dash-02",    new String[]{"d4shp4ss2", "DASHBOARD"});
    }

    public ClientHandler(Socket socket, String sessionId, TelChainServer server) {
        this.socket = socket;
        this.sessionId = sessionId;
        this.server = server;
    }

    /**
     * Loop principal de leitura do socket.
     * Lê linha a linha (protocolo textual) e despacha cada comando.
     */
    @Override
    public void run() {
        String remote = socket.getRemoteSocketAddress().toString();
        System.out.println("[" + sessionId + "] Connected from " + remote);
        try {
            // UTF-8 para garantir compatibilidade.
            out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));

            String line;
            while ((line = in.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                System.out.println("[" + sessionId + "] ← " + line);
                handleMessage(line);
            }
        } catch (IOException e) {
            System.out.println("[" + sessionId + "] Connection closed: " + e.getMessage());
        } finally {
            server.removeClient(this);
            try { socket.close(); } catch (IOException ignored) {}
            System.out.println("[" + sessionId + "] Disconnected (" + remote + ")");
        }
    }

    /**
     * Roteador de mensagens.
     * Protocolo textual para fácil depuração e demonstração, 
     * mas um formato mais ideal seria o binário.
     */
    private void handleMessage(String line) {
        // O split com -1 preserva campos vazios consecutivos.
        String[] parts = line.split("\\|", -1);
        String verb = parts[0].toUpperCase();

        // BYE é sempre permitido, mesmo sem autenticação.
        if (verb.equals("BYE")) {
            send("BYE");
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }

        // HELLO deve ser o primeiro comando e não exige autenticação prévia.
        if (verb.equals("HELLO")) {
            handleHello(parts);
            return;
        }

                // Todos os outros comandos exigem autenticação.
        if (!authenticated) {
            send("ERROR|401|Not authenticated");
            return;
        }

        switch (verb) {
            case "TELEMETRY" -> handleTelemetry(parts);
            case "PING"      -> handlePing(parts);
            case "GETCHAIN"  -> handleGetChain();
            case "VERIFY"    -> handleVerify();
            case "GETBLOCK"  -> handleGetBlock(parts);
            case "SUBSCRIBE" -> handleSubscribe(parts);
            default          -> send("ERROR|400|Unknown verb: " + verb);
        }
    }

    /**
     * Processa o handshake inicial.
     * Verifica credenciais contra o mapa local e estabelece a sessão.
     */
    private void handleHello(String[] parts) {
        if (parts.length < 4) { send("ERROR|400|HELLO requires role|device_id|secret"); return; }

        String reqRole     = parts[1].toUpperCase();
        String reqDeviceId = parts[2];
        String reqSecret   = parts[3];

        String[] creds = CREDENTIALS.get(reqDeviceId);
        // Recusa imediatamente se credenciais inválidas.
        if (creds == null || !creds[0].equals(reqSecret) || !creds[1].equals(reqRole)) {
            send("REJECTED|Invalid credentials");
            return;
        }
        authenticated = true;
        deviceId = reqDeviceId;
        role = reqRole;
        long ts = System.currentTimeMillis() / 1000L;
        send("WELCOME|" + sessionId + "|TelchainServer|" + ts);
        System.out.println("[" + sessionId + "] Authenticated as " + role + " / " + deviceId);
    }

    /**
     * Processa uma leitura de telemetria enviada por um SENSOR.
     * Valida faixas de valores para evitar dados absurdos.
     */
    private void handleTelemetry(String[] parts) {
        if (!role.equals("SENSOR")) { send("ERROR|403|Only SENSOR can report telemetry"); return; }
        if (parts.length < 5) { send("ERROR|400|TELEMETRY requires temp|humidity|pressure|timestamp"); return; }
        try {
            double temp     = Double.parseDouble(parts[1]);
            double humidity = Double.parseDouble(parts[2]);
            double pressure = Double.parseDouble(parts[3]);
            long   ts       = Long.parseLong(parts[4]);

            // Checagem de sanidade.
            if (temp < -100 || temp > 100)     { send("ERROR|400|Temperature out of range"); return; }
            if (humidity < 0 || humidity > 100) { send("ERROR|400|Humidity out of range");    return; }
            if (pressure < 800 || pressure > 1100) { send("ERROR|400|Pressure out of range"); return; }

            Transaction tx = new Transaction(deviceId, temp, humidity, pressure, ts);
            // Notifica dashboards sobre nova transação.
            server.onTransactionReceived(tx);
            // Adiciona à blockchain e retorna um bloco se o limite de pool foi atingido.
            Block newBlock = server.getBlockchain().addTransaction(tx);
            send("ACK|TELEMETRY|" + ts);

            if (newBlock != null) {
                System.out.println("[" + sessionId + "] Threshold reached → " + newBlock);
                // A mineração foi executada dentro de addTransaction/sealBlock.
                server.onBlockMined(newBlock);
            }
        } catch (NumberFormatException e) {
            send("ERROR|400|Invalid numeric fields in TELEMETRY");
        }
    }

    private void handlePing(String[] parts) {
        long ts = (parts.length > 1) ? Long.parseLong(parts[1]) : System.currentTimeMillis() / 1000L;
        send("PONG|" + ts);
    }

    /**
     * Keep‑alive simples.
     * O sensor pode mandar seu timestamp para o servidor responder com um PONG,
     */
    private void handleGetChain() {
        if (!role.equals("DASHBOARD")) { send("ERROR|403|Only DASHBOARD can request chain"); return; }
        Blockchain bc = server.getBlockchain();
        for (Block b : bc.getChain()) {
            send("BLOCK|" + b.index + "|" + b.timestamp + "|" + b.previousHash
                    + "|" + b.merkleRoot + "|" + b.hash + "|" + b.nonce + "|" + b.transactions.size());
            for (int i = 0; i < b.transactions.size(); i++) {
                Transaction tx = b.transactions.get(i);
                send("TX|" + b.index + "|" + i + "|" + tx.deviceId + "|"
                        + tx.temperature + "|" + tx.humidity + "|" + tx.pressure
                        + "|" + tx.timestamp + "|" + tx.hash);
            }
        }
        send("ACK|GETCHAIN|" + (System.currentTimeMillis() / 1000L));
    }

    /**
     * Verifica a integridade da cadeia e retorna o resultado.
     * Assim, o dashboard pode confirmar que o servidor mantém os dados íntegros.
     */
    private void handleVerify() {
        if (!role.equals("DASHBOARD")) { send("ERROR|403|Only DASHBOARD can verify chain"); return; }
        Blockchain bc = server.getBlockchain();
        String error = bc.verify();
        long ts = System.currentTimeMillis() / 1000L;
        if (error == null) {
            send("CHAIN_OK|" + bc.size() + "|" + bc.getTipHash() + "|" + ts);
        } else {
            send("CHAIN_ERR|0|" + error + "|" + ts);
        }
    }

    /**
     * Obtém um bloco específico por índice, com suas transações.
     */
    private void handleGetBlock(String[] parts) {
        if (!role.equals("DASHBOARD")) { send("ERROR|403|Only DASHBOARD can request blocks"); return; }
        if (parts.length < 2) { send("ERROR|400|GETBLOCK requires index"); return; }
        try {
            int idx = Integer.parseInt(parts[1]);
            Block b = server.getBlockchain().getBlock(idx);
            if (b == null) { send("ERROR|404|Block not found: " + idx); return; }
            send("BLOCK|" + b.index + "|" + b.timestamp + "|" + b.previousHash
                    + "|" + b.merkleRoot + "|" + b.hash + "|" + b.nonce + "|" + b.transactions.size());
            for (int i = 0; i < b.transactions.size(); i++) {
                Transaction tx = b.transactions.get(i);
                send("TX|" + b.index + "|" + i + "|" + tx.deviceId + "|"
                        + tx.temperature + "|" + tx.humidity + "|" + tx.pressure
                        + "|" + tx.timestamp + "|" + tx.hash);
            }
            send("ACK|GETBLOCK|" + (System.currentTimeMillis() / 1000L));
        } catch (NumberFormatException e) {
            send("ERROR|400|Invalid block index");
        }
    }

    /**
     * Permite que dashboards assinem tipos de evento (NEW_BLOCK, NEW_TX).
     * Apenas eventos assinados serão enviados pelo servidor via sendEventIfSubscribed.
     */
    private void handleSubscribe(String[] parts) {
        if (!role.equals("DASHBOARD")) { send("ERROR|403|Only DASHBOARD can subscribe"); return; }
        if (parts.length < 2) { send("ERROR|400|SUBSCRIBE requires event_type"); return; }
        String evType = parts[1].toUpperCase();
        subscriptions.add(evType);
        send("ACK|SUBSCRIBE|" + (System.currentTimeMillis() / 1000L));
        System.out.println("[" + sessionId + "] Subscribed to " + evType);
    }

    /**
     * Envia um evento push para este dashboard, se autenticado e inscrito.
     * Chamado pelo servidor de forma assíncrona (pode vir de outras threads).
     */
    public void sendEventIfSubscribed(String eventType, String message) {
        // Verificação de role==DASHBOARD evita envio indevido para sensores,
        // mesmo que o servidor tente.
        if (authenticated && role.equals("DASHBOARD") && subscriptions.contains(eventType)) {
            send(message);
        }
    }

    /**
     * Método interno para enviar uma linha ao cliente.
     * sincronizado para evitar que múltiplas threads corrompam o PrintWriter.
     */
    private synchronized void send(String message) {
        if (out != null) {
            System.out.println("[" + sessionId + "] → " + message);
            out.println(message);
        }
    }

    public String getDeviceId() { return deviceId; }
    public String getRole()     { return role; }
    public String getSessionId(){ return sessionId; }
}