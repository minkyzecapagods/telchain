package telchain.blockchain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Representa a cadeia de blocos mantida em memória.
 * Todas as operações que modificam ou acessam o estado interno são sincronizadas,
 * garantindo que múltiplas threads (ClientHandlers) possam interagir com segurança.
 */
public class Blockchain {
    private final List<Block> chain = new ArrayList<>();
    private final List<Transaction> pendingPool = new ArrayList<>();

    // Após quantas transações pendentes um novo bloco é selado automaticamente.
    // Valor pequeno para demonstração.
    private static final int BLOCK_TX_THRESHOLD = 5;

    // Hash fictício que aponta para "nada", usado pelo bloco gênesis.
    // Comprimento 64 caracteres hex (32 bytes) para combinar com SHA‑256.
    private static final String GENESIS_PREV = "0000000000000000000000000000000000000000000000000000000000000000";

    /**
     * Inicializa a blockchain criando o bloco gênesis (índice 0, sem transações).
     */
    public Blockchain() {
        Block genesis = new Block(0, GENESIS_PREV, new ArrayList<>());
        chain.add(genesis);
        System.out.println("[Blockchain] Genesis block mined: " + genesis.hash);
    }

    /**
     * Adiciona uma transação ao pool pendente.
     * Se o pool atingir o limite, um novo bloco é selado instantaneamente.
     *
     * @return o novo bloco, se selado; null caso contrário.
     *         O retorno é útil para o servidor disparar notificações para dashboards.
     */
    public synchronized Block addTransaction(Transaction tx) {
        pendingPool.add(tx);

        // Selagem automática por volume: garante que as transações não fiquem
        // indefinidamente em memória sem serem commitadas na cadeia.
        if (pendingPool.size() >= BLOCK_TX_THRESHOLD) {
            return sealBlock();
        }
        return null;
    }

    /**
     * Força a selagem de um bloco com as transações pendentes atuais.
     *
     * @return o bloco recém-minerado, ou null se o pool estiver vazio.
     */
    public synchronized Block sealBlock() {
        if (pendingPool.isEmpty()) return null;
        
        // O último bloco da cadeia fornece o previousHash.
        String prevHash = chain.get(chain.size() - 1).hash;

        // Cópia defensiva da lista de transações para isolar o estado do bloco
        // de futuras modificações no pool.
        Block block = new Block(chain.size(), prevHash, new ArrayList<>(pendingPool));
        chain.add(block);

        // Limpa o pool após a mineração.
        pendingPool.clear();
        System.out.println("[Blockchain] New block mined: " + block);
        return block;
    }

    /**
     * Retorna uma vista imutável da cadeia.
     * Impede que clientes externos modifiquem a estrutura interna.
     */
    public synchronized List<Block> getChain() {
        return Collections.unmodifiableList(chain);
    }

    /**
     * Obtém um bloco específico por índice.
     * Retorna null se o índice for inválido.
     */
    public synchronized Block getBlock(int index) {
        if (index < 0 || index >= chain.size()) return null;
        return chain.get(index);
    }

    public synchronized int size() { return chain.size(); }

    public synchronized String getTipHash() { return chain.get(chain.size() - 1).hash; }

    public synchronized int getPendingCount() { return pendingPool.size(); }

    /**
     * Verifica a integridade completa da cadeia.
     * Para cada bloco, checa:
     *   1. Se o hash do bloco é válido (dificuldade + consistência do hash).
     *   2. Se o previousHash aponta para o hash do bloco anterior.
     *
     * @return null se a cadeia é íntegra, ou uma mensagem descritiva do erro.
     */
    public synchronized String verify() {
        // A verificação começa no bloco 1 (índice 1), pois o gênesis não tem predecessor.
        for (int i = 1; i < chain.size(); i++) {
            Block cur = chain.get(i);
            Block prev = chain.get(i - 1);

            if (!cur.isValid()) {
                return "Block " + i + " has invalid hash";
            }
            
            if (!cur.previousHash.equals(prev.hash)) {
                return "Block " + i + " breaks chain link (prev hash mismatch)";
            }
        }

        if (!chain.get(0).isValid()) {
            return "Genesis block has invalid hash";
        }

        return null; // all good
    }

    /**
     * Sumário estatístico útil para logs e monitoramento.
     */
    public synchronized String stats() {
        int totalTx = 0;
        for (Block b : chain) totalTx += b.transactions.size();
        return String.format("Blocks: %d | Transactions: %d | Pending: %d | Tip: %s",
                chain.size(), totalTx, pendingPool.size(), getTipHash().substring(0, 12));
    }
}