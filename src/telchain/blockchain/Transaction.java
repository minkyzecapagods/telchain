package telchain.blockchain;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Representa uma única leitura de telemetria armazenada em um bloco da blockchain.
 * O objeto é imutável e seu hash é calculado automaticamente na construção,
 * servindo como identificador único da transação para verificação de integridade.
 */
public class Transaction {
    // Campos públicos e finais são aceitáveis para um value object simples,
    // desde que a imutabilidade seja garantida e o acesso direto não prejudique manutenção.
    public final String deviceId;
    public final double temperature;
    public final double humidity;
    public final double pressure;
    public final long timestamp;    
    public final String hash;

    /**
     * Cria uma nova transação com os dados informados.
     * O hash é calculado imediatamente para garantir que a transação nunca exista
     * em estado inconsistente (e para evitar cálculos repetidos).
     */
    public Transaction(String deviceId, double temperature, double humidity, double pressure, long timestamp) {
        this.deviceId = deviceId;
        this.temperature = temperature;
        this.humidity = humidity;
        this.pressure = pressure;
        this.timestamp = timestamp;
        this.hash = computeHash();
    }

    /**
     * Calcula o hash SHA‑256 da transação.
     * O uso do delimitador (|) entre os campos evita colisões
     * (ex.: "AB"+"1"+"2" vs "A"+"B1"+"2" produziriam a mesma string).
     */
    private String computeHash() {
        String data = deviceId + "|" + temperature + "|" + humidity + "|" + pressure + "|" + timestamp;
        return sha256(data);
    }

    /**
     * Método utilitário para gerar hash SHA‑256 a partir de uma string.
     * Como SHA‑256 é um algoritmo obrigatório em todas as JVMs, o tratamento
     * de NoSuchAlgorithmException é feito aqui apenas para a API do Java,
     * embora na prática nunca seja lançado.
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            
            // Constrói a representação hexadecimal
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Como a chance de ocorrer é zero, opta-se por uma RuntimeException.
            throw new RuntimeException("Algoritmo SHA‑256 não disponível na JVM", e);
        }
    }

    @Override
    public String toString() {
        return String.format("TX[%s temp=%.1f hum=%.1f pres=%.1f ts=%d hash=%s]",
                deviceId, temperature, humidity, pressure, timestamp, hash.substring(0, 8));
    }
}