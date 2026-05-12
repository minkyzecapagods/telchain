package telchain.server;

import telchain.blockchain.Blockchain;
import telchain.blockchain.Block;
import telchain.blockchain.Transaction;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor central do TelChain.
 * Gerencia a blockchain, autentica clientes e retransmite eventos.
 *
 * Design centralizado: toda comunicação passa pelo servidor (não é P2P),
 * o que simplifica o protocolo e evita problemas de consenso distribuído.
 */
public class TelChainServer {

    public static final int PORT = 7070;

    /**
     * Credenciais fixas para demonstração.
     * Normalmente, estariam em banco de dados protegido, mas isso
     * é suficiente para validar o conceito.
     */    
    private static final Map<String, String[]> CREDENTIALS = new HashMap<>();
    static {
        CREDENTIALS.put("sensor-01",  new String[]{"s3cr3t",   "SENSOR"});
        CREDENTIALS.put("sensor-02",  new String[]{"s3cr3t2",  "SENSOR"});
        CREDENTIALS.put("sensor-03",  new String[]{"s3cr3t3",  "SENSOR"});
        CREDENTIALS.put("dash-01",    new String[]{"d4shp4ss",  "DASHBOARD"});
        CREDENTIALS.put("dash-02",    new String[]{"d4shp4ss2", "DASHBOARD"});
    }

    /**
     * Estado central da blockchain – thread‑safe por si só.
     */
    private final Blockchain blockchain = new Blockchain();

    /**
     * Lista de clientes conectados.
     *
     * CopyOnWriteArrayList é usada para permitir iterações seguras enquanto
     * há modificações (adição/remoção de handlers) sem bloqueio explícito.
     * Como o broadcast é disparado de múltiplas threads (timer + handlers),
     * essa escolha evita concorrência ao percorrer a lista.
     */
    private final List<ClientHandler> activeClients = new CopyOnWriteArrayList<>();
    
    /**
     * Contador atômico para gerar IDs de sessão únicos.
     * Evita sincronização manual.
     */
    private final AtomicInteger sessionCounter = new AtomicInteger(1);

    /**
     * Executor agendado de thread única para selagem periódica de blocos.
     * Uma única thread é suficiente porque a operação é rápida.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    /**
     * Inicializa o servidor, agenda a selagem por timer e entra no loop de accept.
     */
    public void start() throws IOException {
        // Selagem periódica: garante que mesmo baixos volumes de transações
        // sejam commitados em bloco após 30 segundos, evitando latência infinita.
        scheduler.scheduleAtFixedRate(() -> {
            Block b = blockchain.sealBlock();
            if (b != null) {
                System.out.println("[Server] Timer-sealed block #" + b.index);
                broadcastEvent("NEW_BLOCK", b.index + "|" + b.hash + "|" + b.transactions.size());
            }
        }, 30, 30, TimeUnit.SECONDS);

        ServerSocket serverSocket = new ServerSocket(PORT);
        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║           TelChain Server            ║");
        System.out.println("║     Listening on port " + PORT + "           ║");
        System.out.println("╚══════════════════════════════════════╝");
        System.out.println("[Server] " + blockchain.stats());

        // Hook de desligamento limpo: interrompe o scheduler e encerra
        // a thread de aceitação de forma ordenada.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Server] Shutting down...");
            scheduler.shutdown();
        }));

        while (true) {
            Socket clientSocket = serverSocket.accept();
            String sessionId = "sess-" + String.format("%04d", sessionCounter.getAndIncrement());
            ClientHandler handler = new ClientHandler(clientSocket, sessionId, this);
            activeClients.add(handler);
            // Cada cliente é tratado em uma thread própria para não bloquear
            // o servidor enquanto um cliente envia dados.
            new Thread(handler, "client-" + sessionId).start();
        }
    }

    /**
     * Chamado pelo ClientHandler quando o cliente se desconecta.
     * A remoção da lista pode ocorrer durante um broadcast, mas a
     * CopyOnWriteArrayList evita problemas de concorrência.
     */
    void removeClient(ClientHandler handler) {
        activeClients.remove(handler);
    }

    /**
     * Notifica todos os dashboards sobre um novo bloco minerado.
     * Pode ser chamado a partir de ClientHandlers quando o método
     * {@link #addTransaction} retorna um bloco não nulo.
     */
    void onBlockMined(Block block) {
        broadcastEvent("NEW_BLOCK", block.index + "|" + block.hash + "|" + block.transactions.size());
    }

    /**
     * Notifica dashboards sobre uma nova transação recebida.
     * Permite visualização em tempo real sem esperar a selagem do bloco.
     */
    void onTransactionReceived(Transaction tx) {
        broadcastEvent("NEW_TX", tx.deviceId + "|" + tx.temperature + "|" + tx.humidity + "|" + tx.pressure);
    }

    /**
     * Envia um evento formatado para todos os clientes do tipo DASHBOARD.
     * CopyOnWriteArrayList garante que a iteração seja segura mesmo com modificações na lista.
     */
    private void broadcastEvent(String type, String payload) {
        String msg = "EVENT|" + type + "|" + payload;
        for (ClientHandler c : activeClients) {
            c.sendEventIfSubscribed(type, msg);
        }
    }

    public Blockchain getBlockchain() { return blockchain; }

    /**
     * Ponto de entrada: cria e inicia o servidor.
     */
    public static void main(String[] args) throws Exception {
        new TelChainServer().start();
    }
}