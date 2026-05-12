package telchain.blockchain;

import java.util.ArrayList;
import java.util.List;

/**
 * Representa um bloco da blockchain didática.
 * PoW simples: encontrar um nonce tal que SHA‑256 (dados do bloco + nonce)
 * comece com "00" (1 byte zero).
 * O hash é calculado sobre (índice | timestamp | previousHash | merkleRoot | nonce),
 * com separadores para evitar colisões entre campos concatenados.
 */
public class Block {
    // Campos do bloco: todos imutáveis, exceto hash e nonce (calculados no mining)
    public final int index;
    public final long timestamp;
    public final String previousHash;
    public final List<Transaction> transactions;
    public final String merkleRoot;
    public String hash;
    public int nonce;

    // Prefixo de dificuldade: 2 caracteres hexadecimais = 1 byte zero
    private static final String DIFFICULTY_PREFIX = "00";

    /**
     * Construtor para criação de um novo bloco (após coleta de transações).
     * A timestamp é fixada no instante da construção, a raiz de Merkle é calculada
     * e o processo de mineração é executado imediatamente. Isso garante que o bloco
     * nunca exista sem um hash válido.
     */
    public Block(int index, String previousHash, List<Transaction> transactions) {
        this.index = index;
        this.timestamp = System.currentTimeMillis() / 1000L; // precisão de segundos é suficiente
        this.previousHash = previousHash;
        this.transactions = new ArrayList<>(transactions);
        this.merkleRoot = computeMerkleRoot(this.transactions);
        mine(); // define hash e nonce
    }

    /**
     * Construtor para reconstrução a partir de dados serializados.
     * Todos os campos são fornecidos explicitamente e não há mineração.
     */
    public Block(int index, long timestamp, String previousHash, String merkleRoot,
                 String hash, int nonce, List<Transaction> transactions) {
        this.index = index;
        this.timestamp = timestamp;
        this.previousHash = previousHash;
        this.merkleRoot = merkleRoot;
        this.hash = hash;
        this.nonce = nonce;
        this.transactions = new ArrayList<>(transactions);
    }

    /**
     * Realiza a mineração: incrementa o nonce até que o hash atenda à dificuldade.
     * O nonce começa de 0, mas a primeira tentativa usa nonce=1 (incremento antes do cálculo).
     * Em uma blockchain real, a dificuldade seria muito maior e dinâmica, 
     * mas, para esta demonstração, o prefixo "00" é suficiente.
     */
    private void mine() {
        nonce = 0;
        do {
            nonce++;
            hash = computeHash(nonce);
        } while (!hash.startsWith(DIFFICULTY_PREFIX));
    }

    /**
     * Calcula o hash do bloco para um nonce candidato.
     * O uso de "|" entre os campos evita que concatenações ingênuas produzam
     * a mesma string para combinações diferentes (ex.: "1"+"23" vs "12"+"3").
     */
    public String computeHash(int nonce) {
        String data = index + "|" + timestamp + "|"
                      + previousHash + "|" + merkleRoot + "|" + nonce;
        return Transaction.sha256(data);
    }

    /**
     * Verifica se o bloco é íntegro: se o hash armazenado confere com o hash recalculado
     * e se atende à dificuldade atual. Usado por clientes que auditam a cadeia.
     */
    public boolean isValid() {
        return hash.equals(computeHash(nonce)) && hash.startsWith(DIFFICULTY_PREFIX);
    }

    /**
     * Calcula a raiz de Merkle para uma lista de transações.
     * Se a lista for vazia, retorna o hash da string "EMPTY" (sentinela).
     * Do contrário, faz o hash das transações em pares, repetindo o último
     * se o número for ímpar, até sobrar um único hash.
     */
    private static String computeMerkleRoot(List<Transaction> txs) {
        if (txs.isEmpty()) return Transaction.sha256("EMPTY");

        List<String> hashes = new ArrayList<>();
        for (Transaction tx : txs) hashes.add(tx.hash);
        while (hashes.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < hashes.size(); i += 2) {
                String left = hashes.get(i);
                // Se não houver par, duplica o último elemento
                String right = (i + 1 < hashes.size()) ? hashes.get(i + 1) : left;
                next.add(Transaction.sha256(left + right));
            }
            hashes = next;
        }
        return hashes.get(0);
    }

    @Override
    public String toString() {
        return String.format("Block[%d hash=%s prev=%s txs=%d nonce=%d]",
                index, hash.substring(0, 8), previousHash.substring(0, 8),
                transactions.size(), nonce);
    }
}