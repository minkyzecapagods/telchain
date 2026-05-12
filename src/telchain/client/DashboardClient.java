package telchain.client;

import java.io.*;
import java.net.Socket;
import java.util.*;

/**
 * Cliente Dashboard do TelChain.
 *
 * Dashboard interativo para monitoramento da blockchain.
 * Recebe eventos push em tempo real e fornece comandos para inspecionar a cadeia.
 *
 * Uso:
 *   java telchain.client.DashboardClient [host] [port] [device_id] [secret]
 *
 * Defaults:
 *   host=localhost, port=7070, device=dash-01, secret=d4shp4ss
 *
 * Comandos:
 *   chain   - mostra toda a cadeia de blocos (índices, hashes, contagem de transações)
 *   verify  - verifica integridade da cadeia
 *   block N - mostra detalhes do bloco N
 *   stats   - mostra status da sessão
 *   sub     - inscrever-se aos eventos NEW_BLOCK + NEW_TX
 *   ping    - manda ping
 *   help    - mostra ajuda
 *   quit    - disconecta
 */
public class DashboardClient {

    private final String host;
    private final int port;
    private final String deviceId;
    private final String secret;

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;
    // volatile para visibilidade imediata entre a thread leitora e a principal
    private volatile boolean running = false;

    // Estatísticas da sessão (mantidas apenas para exibição local)
    private int totalBlocks = 0;
    private int totalTx = 0;
    // Log de eventos recebidos; acessado por múltiplas threads
    private final List<String> eventLog = new ArrayList<>();

    public DashboardClient(String host, int port, String deviceId, String secret) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.secret = secret;
    }

    /** Estabelece a conexão TCP com o servidor. */
    public void connect() throws IOException {
        socket = new Socket(host, port);
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        System.out.println("[Dashboard] Connected to " + host + ":" + port);
    }

    /** Handshake inicial: envia HELLO e aguarda WELCOME. */
    public boolean authenticate() throws IOException {
        send("HELLO|DASHBOARD|" + deviceId + "|" + secret);
        String response = in.readLine();
        if (response == null) return false;
        if (response.startsWith("WELCOME")) {
            String[] parts = response.split("\\|");
            System.out.println("[Dashboard] ✓ Authenticated! Session: " + parts[1]);
            return true;
        }
        System.err.println("[Dashboard] ✗ Auth failed: " + response);
        return false;
    }

    /**
     * Loop principal: inicia a thread leitora de eventos e o prompt interativo.
     *
     * A thread leitora é separada para que a chegada de eventos push (NEW_BLOCK, etc.)
     * não seja bloqueada enquanto o usuário digita comandos.
     */
    public void run() throws IOException {
        running = true;

        Thread reader = new Thread(this::readLoop, "dash-reader");
        reader.setDaemon(true);
        reader.start();

        // Assinatura automática para receber eventos em tempo real
        send("SUBSCRIBE|NEW_BLOCK");
        send("SUBSCRIBE|NEW_TX");
        send("SUBSCRIBE|ALERT");

        printBanner();

        Scanner scanner = new Scanner(System.in);
        System.out.print("\n> ");
        while (running && scanner.hasNextLine()) {
            String cmd = scanner.nextLine().trim().toLowerCase();
            if (cmd.isEmpty()) { System.out.print("> "); continue; }
            processCommand(cmd);
            if (running) System.out.print("> ");
        }
    }

    /** Roteador de comandos do usuário. */
    private void processCommand(String cmd) {
        String[] parts = cmd.split("\\s+", 2);
        switch (parts[0]) {
            case "chain" -> {
                System.out.println("\n[Dashboard] Requesting full blockchain...");
                send("GETCHAIN");
            }
            case "verify" -> {
                System.out.println("\n[Dashboard] Requesting chain verification...");
                send("VERIFY");
            }
            case "block" -> {
                if (parts.length < 2) { System.out.println("Usage: block <index>"); break; }
                send("GETBLOCK|" + parts[1]);
            }
            case "stats" -> printStats();
            case "sub" -> {
                send("SUBSCRIBE|NEW_BLOCK");
                send("SUBSCRIBE|NEW_TX");
                System.out.println("[Dashboard] Subscribed to all events.");
            }
            case "ping" -> send("PING|" + (System.currentTimeMillis() / 1000L));
            case "log"  -> printEventLog();
            case "help" -> printHelp();
            case "quit", "exit", "bye" -> {
                send("BYE");
                running = false;
            }
            default -> System.out.println("Unknown command. Type 'help' for available commands.");
        }
    }

    /** Thread dedicada a receber mensagens do servidor. */
    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                parseServerMessage(line);
            }
        } catch (IOException e) {
            if (running) System.out.println("\n[Dashboard] Server connection lost: " + e.getMessage());
        }
        running = false;
    }

    /**
     * Interpreta uma linha recebida do servidor.
     *
     * O protocolo é baseado em prefixo (BLOCK, TX, CHAIN_OK, etc.).
     */
    private void parseServerMessage(String line) {
        String[] parts = line.split("\\|", -1);
        switch (parts[0]) {
            case "BLOCK" -> {
                // BLOCK|index|timestamp|prevHash|merkleRoot|hash|nonce|txCount
                if (parts.length >= 8) {
                    String idx     = parts[1];
                    String ts      = parts[2];
                    String prevHash= parts[3];
                    String mroot   = parts[4];
                    String hash    = parts[5];
                    String nonce   = parts[6];
                    String txCount = parts[7];

                    System.out.println("\n" + "─".repeat(60));
                    System.out.printf("  BLOCK #%s%n", idx);
                    System.out.printf("  Hash:       %s%n", hash.substring(0, Math.min(20, hash.length())) + "...");
                    System.out.printf("  Prev Hash:  %s%n", prevHash.substring(0, Math.min(20, prevHash.length())) + "...");
                    System.out.printf("  Merkle Root:%s%n", mroot.substring(0, Math.min(20, mroot.length())) + "...");
                    System.out.printf("  Timestamp:  %s%n", new java.util.Date(Long.parseLong(ts) * 1000L));
                    System.out.printf("  Nonce:      %s%n", nonce);
                    System.out.printf("  Tx Count:   %s%n", txCount);
                    System.out.println("─".repeat(60));

                    totalBlocks = Math.max(totalBlocks, Integer.parseInt(idx) + 1);
                }
            }
            case "TX" -> {
                // TX|blockIdx|txIdx|deviceId|temp|hum|pres|timestamp|txHash
                if (parts.length >= 9) {
                    System.out.printf("    TX[%s] Dev:%-10s Temp:%5s°C Hum:%5s%% Pres:%6s hPa  hash:%s%n",
                            parts[2], parts[3], parts[4], parts[5], parts[6],
                            parts[8].substring(0, 8) + "...");
                    totalTx++;
                }
            }
            case "CHAIN_OK" -> {
                // CHAIN_OK|blockCount|tipHash|timestamp
                System.out.println("\n╔═══════════════════════════════════════╗");
                System.out.println("║  ✓  CHAIN INTEGRITY: VALID            ║");
                System.out.printf( "║  Blocks: %-29s║%n", parts[1]);
                System.out.printf( "║  Tip:    %-29s║%n", parts[2].substring(0, 16) + "...");
                System.out.println("╚═══════════════════════════════════════╝");
            }
            case "CHAIN_ERR" -> {
                System.out.println("\n╔═══════════════════════════════════════╗");
                System.out.println("║  ✗  CHAIN INTEGRITY: INVALID!         ║");
                System.out.printf( "║  At block: %-29s║%n", parts[1]);
                System.out.printf( "║  Reason:   %-29s║%n", parts[2]);
                System.out.println("╚═══════════════════════════════════════╝");
            }
            case "EVENT" -> {
                // EVENT|type|payload
                String evType   = parts.length > 1 ? parts[1] : "?";
                String payload  = parts.length > 2 ? String.join("|", Arrays.copyOfRange(parts, 2, parts.length)) : "";
                String logEntry = "[" + new java.util.Date() + "] EVENT:" + evType + " | " + payload;
                synchronized (eventLog) { eventLog.add(logEntry); }

                switch (evType) {
                    case "NEW_BLOCK" ->                     {
                            String[] ep = payload.split("\\|");
                            System.out.println("\n NEW BLOCK mined! Index: " + ep[0]
                                    + "  Hash: " + (ep.length > 1 ? ep[1].substring(0, 12) : "?") + "..."
                                            + "  Txs: " + (ep.length > 2 ? ep[2] : "?"));
                        }
                    case "NEW_TX" ->                     {
                            String[] ep = payload.split("\\|");
                            System.out.printf(" New telemetry from %-10s  Temp:%5s°C  Hum:%5s%%  Pres:%6s hPa%n",
                                    ep.length > 0 ? ep[0] : "?",
                                    ep.length > 1 ? ep[1] : "?",
                                    ep.length > 2 ? ep[2] : "?",
                                    ep.length > 3 ? ep[3] : "?");
                        }
                    default -> System.out.println(" EVENT [" + evType + "]: " + payload);
            }
            }
            case "ACK"  -> System.out.println("[Dashboard] ACK: " + (parts.length > 1 ? parts[1] : ""));
            case "PONG" -> System.out.println("[Dashboard] PONG ← server alive");
            case "ERROR"-> System.err.println("[Dashboard] SERVER ERROR " + parts[1] + ": " + (parts.length > 2 ? parts[2] : ""));
            case "BYE"  -> { System.out.println("[Dashboard] Server closed connection."); running = false; }
            default     -> System.out.println("[Dashboard] ← " + line);
        }
    }

    /** Imprime o banner de boas-vindas e informações da sessão. */
    private void printBanner() {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════╗");
        System.out.println("║          TelChain Dashboard - Live Monitor               ║");
        System.out.println("║  Device: " + padRight(deviceId, 48) + "║");
        System.out.println("║  Server: " + padRight(host + ":" + port, 48) + "║");
        System.out.println("╠══════════════════════════════════════════════════════════╣");
        System.out.println("║  Subscribed to: NEW_BLOCK | NEW_TX | ALERT               ║");
        System.out.println("║  Type 'help' for available commands                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════╝");
    }

    /** Imprime as instruções de ajuda para o usuário. */
    private void printHelp() {
        System.out.println();
        System.out.println("Available commands:");
        System.out.println("  chain      - Dump full blockchain (all blocks and transactions)");
        System.out.println("  verify     - Verify chain integrity (hash chain validation)");
        System.out.println("  block <N>  - Show details of block N");
        System.out.println("  stats      - Show session statistics");
        System.out.println("  log        - Show recent event log");
        System.out.println("  sub        - Re-subscribe to all events");
        System.out.println("  ping       - Send keepalive ping");
        System.out.println("  help       - Show this help");
        System.out.println("  quit       - Disconnect from server");
    }

    /** Imprime as estatísticas da sessão. */
    private void printStats() {
        System.out.println();
        System.out.println("─── Session Stats ───────────────────────────");
        System.out.println("  Device ID   : " + deviceId);
        System.out.println("  Server      : " + host + ":" + port);
        System.out.println("  Blocks seen : " + totalBlocks);
        System.out.println("  TX received : " + totalTx);
        System.out.println("  Events logged: " + eventLog.size());
        System.out.println("─────────────────────────────────────────────");
    }

    /** Imprime o log de eventos. */
    private void printEventLog() {
        synchronized (eventLog) {
            if (eventLog.isEmpty()) { System.out.println("(no events yet)"); return; }
            System.out.println("\n─── Event Log (" + eventLog.size() + " entries) ───────────────");
            int start = Math.max(0, eventLog.size() - 20); // last 20 events
            for (int i = start; i < eventLog.size(); i++) {
                System.out.println("  " + eventLog.get(i));
            }
            System.out.println("─────────────────────────────────────────────");
        }
    }

    /** Envia uma linha ao servidor. Thread principal apenas. */
    private void send(String msg) {
        if (out != null) out.println(msg);
    }


    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    /* Ponto de entrada: parseia argumentos e inicia o cliente. */
    public static void main(String[] args) throws Exception {
        String host     = args.length > 0 ? args[0] : "localhost";
        int    port     = args.length > 1 ? Integer.parseInt(args[1]) : 7070;
        String deviceId = args.length > 2 ? args[2] : "dash-01";
        String secret   = args.length > 3 ? args[3] : "d4shp4ss";

        DashboardClient client = new DashboardClient(host, port, deviceId, secret);
        client.connect();
        if (client.authenticate()) {
            client.run();
        } else {
            System.exit(1);
        }
    }
}