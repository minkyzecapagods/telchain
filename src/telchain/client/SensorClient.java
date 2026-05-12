package telchain.client;

import java.io.*;
import java.net.Socket;
import java.util.Random;

/**
 * Cliente Sensor do TelChain.
 * 
 * Simula um sensor IoT ambiental que periodicamente lê e reporta
 * temperatura, umidade e pressão atmosférica ao servidor TelChain.
 *
 * Utiliza um modelo de "random walk" para gerar dados realistas.
 * 
 * Uso:
 *   java telchain.client.SensorClient [host] [port] [device_id] [secret] [interval_ms]
 *
 * Defaults:
 *   host=localhost, port=7070, device=sensor-01, secret=s3cr3t, interval=5000ms
 */
public class SensorClient {

    private final String host;
    private final int port;
    private final String deviceId;
    private final String secret;
    private final long intervalMs; // intervalo entre leituras

    private Socket socket;
    private PrintWriter out;
    private BufferedReader in;

    // Flag de controle para encerramento limpo
    private boolean running = false;

    // Estado interno do sensor simulado (random walk)
    private double temperature = 22.0;
    private double humidity    = 55.0;
    private double pressure    = 1013.0;
    private final Random rand = new Random();

    public SensorClient(String host, int port, String deviceId, String secret, long intervalMs) {
        this.host = host;
        this.port = port;
        this.deviceId = deviceId;
        this.secret = secret;
        this.intervalMs = intervalMs;
    }

    /**
     * Estabelece a conexão TCP e inicializa os canais de comunicação.
     */
    public void connect() throws IOException {
        socket = new Socket(host, port);
        out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
        in  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
        System.out.println("[Sensor] Connected to " + host + ":" + port);
    }

    /**
     * Realiza o handshake HELLO e aguarda a resposta WELCOME/REJECTED.
     * O protocolo exige autenticação antes de qualquer outro comando.
     */
    public boolean authenticate() throws IOException {
        send("HELLO|SENSOR|" + deviceId + "|" + secret);
        String response = readLine();
        if (response == null) { System.err.println("[Sensor] No response from server"); return false; }

        if (response.startsWith("WELCOME")) {
            String[] parts = response.split("\\|");
            System.out.println("[Sensor] ✓ Authenticated! Session: " + parts[1] + " | Server: " + parts[2]);
            return true;
        } else if (response.startsWith("REJECTED")) {
            System.err.println("[Sensor] ✗ Authentication failed: " + response);
            return false;
        }
        System.err.println("[Sensor] Unexpected response: " + response);
        return false;
    }

    /**
     * Loop principal de envio de telemetria.
     * Dispara uma thread separada para leitura de respostas assíncronas do servidor,
     * enquanto a thread principal cuida do envio periódico.
     */
    public void run() throws IOException, InterruptedException {
        running = true;
        // Thread leitora: evita que a leitura bloqueie o envio.
        // Configurada como daemon para não impedir o encerramento da JVM.
        Thread reader = new Thread(this::readLoop, "sensor-reader");
        reader.setDaemon(true);
        reader.start();

        int txCount = 0;
        System.out.println("[Sensor] Starting telemetry loop (interval: " + intervalMs + "ms)");
        System.out.println("[Sensor] Press Ctrl+C to stop\n");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[Sensor] Sending BYE...");
            send("BYE");
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }));

        while (running && !socket.isClosed()) {
            simulateSensorStep();
            long ts = System.currentTimeMillis() / 1000L;

            String msg = String.format("TELEMETRY|%.1f|%.1f|%.1f|%d",
                    temperature, humidity, pressure, ts);
            send(msg);
            txCount++;

            System.out.printf("[Sensor] #%d → Temp: %.1f°C  Hum: %.1f%%  Pres: %.1f hPa%n",
                    txCount, temperature, humidity, pressure);

            Thread.sleep(intervalMs);

            // Ping periódico a cada 10 leituras mantém o keep‑alive
            // e permite ao servidor (e ao dashboard) medir latência.
            if (txCount % 10 == 0) {
                send("PING|" + (System.currentTimeMillis() / 1000L));
            }
        }
    }

    /**
     * Evolui o estado do sensor usando um "random walk" gaussiano.
     * Pequenas variações aleatórias (±0.5°C, etc.) simulam mudanças
     * ambientais graduais, mais realistas que saltos aleatórios.
     */
    private void simulateSensorStep() {
        temperature = clamp(temperature + (rand.nextGaussian() * 0.5), -10.0, 60.0);
        humidity    = clamp(humidity    + (rand.nextGaussian() * 1.0),   0.0, 100.0);
        pressure    = clamp(pressure    + (rand.nextGaussian() * 0.3), 950.0, 1050.0);
    }

    /**
     * Loop de leitura executado em thread separada.
     * Recebe ACKs, PONGs, mensagens de erro e o BYE do servidor.
     * A flag 'running' é usada para sinalizar à thread principal que deve parar.
     */
    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }

    private void readLoop() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                parseServerMessage(line);
            }
        } catch (IOException e) {
            if (running) System.out.println("[Sensor] Server closed connection: " + e.getMessage());
        }
        running = false;
    }

    /**
     * Interpreta mensagens recebidas do servidor.
     * O switch sobre o verbo permite tratamento específico e legível.
     */
    private void parseServerMessage(String line) {
        String[] parts = line.split("\\|", -1);
        switch (parts[0]) {
            case "ACK"   -> System.out.println("[Sensor] ← ACK received for " + (parts.length > 1 ? parts[1] : "?"));
            case "PONG"  -> System.out.println("[Sensor] ← PONG received");
            case "ERROR" -> System.err.println("[Sensor] ← SERVER ERROR " + parts[1] + ": " + (parts.length > 2 ? parts[2] : ""));
            case "BYE"   -> { System.out.println("[Sensor] ← Server said BYE"); running = false; }
            default      -> System.out.println("[Sensor] ← " + line);
        }
    }

    /**
     * Envia uma linha ao servidor.
     * Não necessita de synchronized porque apenas a thread principal envia dados.
     */
    private void send(String msg) {
        if (out != null) {
            System.out.println("[Sensor] → " + msg);
            out.println(msg);
        }
    }

    private String readLine() throws IOException {
        return in.readLine();
    }

    /**
     * Ponto de entrada: parseia argumentos e inicia o cliente.
     */
    public static void main(String[] args) throws Exception {
        String host     = args.length > 0 ? args[0] : "localhost";
        int    port     = args.length > 1 ? Integer.parseInt(args[1]) : 7070;
        String deviceId = args.length > 2 ? args[2] : "sensor-01";
        String secret   = args.length > 3 ? args[3] : "s3cr3t";
        long   interval = args.length > 4 ? Long.parseLong(args[4]) : 5000L;

        System.out.println("╔══════════════════════════════════════╗");
        System.out.println("║   TelChain Sensor Client             ║");
        System.out.println("║   Device: " + padRight(deviceId, 27) + "║");
        System.out.println("║   Target: " + padRight(host + ":" + port, 27) + "║");
        System.out.println("╚══════════════════════════════════════╝");

        SensorClient client = new SensorClient(host, port, deviceId, secret, interval);
        client.connect();
        if (client.authenticate()) {
            client.run();
        } else {
            System.exit(1);
        }
    }

    private static String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }
}