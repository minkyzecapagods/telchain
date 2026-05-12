# 🔗 TelChain

> Telemetria de Sensores IoT com Registro em Blockchain  
> Aplicação Cliente/Servidor — DIM0438 Redes de Computadores · UFRN · BTI

---

## Sumário

- [Sobre o Projeto](#sobre-o-projeto)
- [Arquitetura](#arquitetura)
- [Estrutura do Repositório](#estrutura-do-repositório)
- [Protocolo TelChain](#protocolo-telchain)
- [Pré-requisitos](#pré-requisitos)
- [Build](#build)
- [Execução](#execução)
- [Credenciais](#credenciais)
- [Comandos do Dashboard](#comandos-do-dashboard)
- [Como a Blockchain Funciona](#como-a-blockchain-funciona)

---

## Sobre o Projeto

**TelChain** é uma aplicação distribuída que combina telemetria de sensores IoT com registro imutável de dados em uma blockchain didática.

- **Sensores** publicam leituras periódicas de temperatura, umidade e pressão atmosférica
- O **servidor** armazena essas leituras em blocos encadeados protegidos por Prova de Trabalho (SHA-256)
- **Dashboards** monitoram a cadeia em tempo real via eventos *push* e auditam a integridade criptográfica

---

## Arquitetura

```
  [Sensor 01] ──TELEMETRY──┐
  [Sensor 02] ──TELEMETRY──┤         ┌──EVENT push──► [Dashboard 01]
  [Sensor 03] ──TELEMETRY──► SERVER  │
                            :7070 ───┤──EVENT push──► [Dashboard 02]
                              │      └──GETCHAIN/VERIFY (pull)
                              ▼
                        [Blockchain]
                        (em memória)
```

O servidor usa o modelo **thread-per-client**: cada conexão recebe uma thread dedicada. A `Blockchain` é thread-safe via `synchronized`. A lista de clientes ativos usa `CopyOnWriteArrayList` para broadcasts seguros.

---

## Estrutura do Repositório

```
telchain/
├── build.sh
├── docs/     
|   ├── especificacao.pdf       # Especificação da aplicação
|   └── especificacao.tex
└── src/telchain/
    ├── blockchain/
    │   ├── Block.java          # Bloco com PoW e raiz de Merkle
    │   ├── Blockchain.java     # Cadeia thread-safe + pool de transações
    │   └── Transaction.java    # Leitura de telemetria (value object imutável)
    ├── client/
    │   ├── SensorClient.java   # Sensor IoT simulado (random walk gaussiano)
    │   └── DashboardClient.java # Monitor interativo com eventos push
    └── server/
        ├── TelChainServer.java  # Aceita conexões, agenda selagem de blocos
        └── ClientHandler.java   # Parser do protocolo + despacho de comandos
```

---

## Protocolo TelChain

Protocolo proprietário orientado a texto sobre **TCP porta 7070**. Uma mensagem por linha, campos separados por `|`.

### Autenticação

| Direção | Mensagem |
|---------|----------|
| C → S | `HELLO\|<role>\|<device_id>\|<secret>` |
| S → C | `WELCOME\|<session_id>\|TelChainServer` |
| S → C | `REJECTED\|<motivo>` |

`role` ∈ `{SENSOR, DASHBOARD}`

### Sensor

| Direção | Mensagem |
|---------|----------|
| C → S | `TELEMETRY\|<temp>\|<hum>\|<pres>\|<timestamp>` |
| S → C | `ACK\|TELEMETRY\|<timestamp>` |

### Dashboard — Consultas

| Direção | Mensagem |
|---------|----------|
| C → S | `GETCHAIN` |
| C → S | `GETBLOCK\|<index>` |
| C → S | `VERIFY` |
| S → C | `BLOCK\|<idx>\|<ts>\|<prevHash>\|<merkleRoot>\|<hash>\|<nonce>\|<txCount>` |
| S → C | `TX\|<blockIdx>\|<txIdx>\|<devId>\|<temp>\|<hum>\|<pres>\|<ts>\|<hash>` |
| S → C | `CHAIN_OK\|<blockCount>\|<tipHash>\|<timestamp>` |
| S → C | `CHAIN_ERR\|<blockIdx>\|<motivo>` |

### Dashboard — Eventos Push

| Direção | Mensagem |
|---------|----------|
| C → S | `SUBSCRIBE\|<NEW_BLOCK \| NEW_TX>` |
| S → C | `EVENT\|NEW_BLOCK\|<idx>\|<hash>\|<txCount>` |
| S → C | `EVENT\|NEW_TX\|<devId>\|<temp>\|<hum>\|<pres>` |

### Utilitários

| Direção | Mensagem |
|---------|----------|
| C → S | `PING\|<timestamp>` |
| S → C | `PONG\|<timestamp>` |
| C → S / S → C | `BYE` |
| S → C | `ERROR\|<código>\|<mensagem>` |

---

## Pré-requisitos

- **JDK ≥ 17** (OpenJDK, Eclipse Temurin, Oracle JDK)
- **Bash** (Linux/macOS nativo; Windows: WSL2 ou Git Bash)
- Porta **7070/TCP** disponível

```bash
java -version   # deve exibir 17+
javac -version
```

---

## Build

A partir da raiz do projeto:

```bash
chmod +x build.sh
./build.sh
```

O script compila todos os fontes e gera três JARs executáveis:

| JAR | Conteúdo |
|-----|----------|
| `telchain-server.jar` | Servidor TelChain |
| `telchain-sensor.jar` | Cliente Sensor |
| `telchain-dashboard.jar` | Cliente Dashboard |

---

## Execução

Abra **quatro terminais** a partir da raiz do projeto:

```bash
# Terminal 1 — Servidor
java -jar telchain-server.jar
```

```bash
# Terminal 2 — Sensor 01 (intervalo de 3 s)
java -jar telchain-sensor.jar localhost 7070 sensor-01 s3cr3t 3000
```

```bash
# Terminal 3 — Sensor 02 (intervalo de 4 s)
java -jar telchain-sensor.jar localhost 7070 sensor-02 s3cr3t2 4000
```

```bash
# Terminal 4 — Dashboard
java -jar telchain-dashboard.jar localhost 7070 dash-01 d4shp4ss
```

### Argumentos

**Sensor:** `[host] [port] [device_id] [secret] [interval_ms]`  
**Dashboard:** `[host] [port] [device_id] [secret]`

Todos os argumentos são opcionais, os valores padrão são os usados nos exemplos acima.

### Execução em hosts diferentes

Substitua `localhost` pelo IP do servidor:

```bash
java -jar telchain-sensor.jar <SERVER-IP> 7070 sensor-01 s3cr3t 5000
```

> Certifique-se de que a porta 7070/TCP está liberada no firewall do servidor.

---

## Credenciais

| Device ID | Senha | Papel |
|-----------|-------|-------|
| `sensor-01` | `s3cr3t` | SENSOR |
| `sensor-02` | `s3cr3t2` | SENSOR |
| `sensor-03` | `s3cr3t3` | SENSOR |
| `dash-01` | `d4shp4ss` | DASHBOARD |
| `dash-02` | `d4shp4ss2` | DASHBOARD |

---

## Comandos do Dashboard

Após conectar, o dashboard entra em modo interativo:

| Comando | Descrição |
|---------|-----------|
| `chain` | Dump completo da blockchain (todos os blocos e transações) |
| `verify` | Verificação de integridade criptográfica da cadeia |
| `block <N>` | Detalhes do bloco de índice N |
| `stats` | Estatísticas da sessão local |
| `log` | Exibe os últimos 20 eventos recebidos |
| `sub` | Re-inscrição em todos os eventos |
| `ping` | Envia PING e aguarda PONG |
| `help` | Exibe ajuda |
| `quit` | Encerra a sessão |

---

## Como a Blockchain Funciona

### Transação

Cada leitura de sensor gera uma transação com hash SHA-256 dos campos:

```
hash = SHA-256(deviceId | temperature | humidity | pressure | timestamp)
```

### Bloco

Campos: `index`, `timestamp`, `previousHash`, `merkleRoot`, `nonce`, `hash`, `transactions[]`

O hash do bloco é calculado sobre:
```
SHA-256(index | timestamp | previousHash | merkleRoot | nonce)
```

### Prova de Trabalho (PoW)

O servidor incrementa o `nonce` até que o hash do bloco comece com `"00"` (1 byte zero). A mineração é automática e ocorre em dois cenários:

- **Por volume:** ao atingir **5 transações** pendentes no pool
- **Por tempo:** a cada **30 segundos**, se houver ao menos 1 transação pendente

### Raiz de Merkle

Calculada recursivamente sobre os hashes das transações em pares. Se o número de nós for ímpar, o último é duplicado. Listas vazias produzem `SHA-256("EMPTY")`.

### Verificação de Integridade

O comando `verify` (dashboard) valida toda a cadeia: para cada bloco, confirma que o hash é válido (atende à dificuldade) e que `previousHash` aponta corretamente para o bloco anterior.

---

> **Atenção:** a blockchain é mantida **exclusivamente em memória**. Ao reiniciar o servidor, a cadeia é reiniciada com um novo bloco gênesis e todos os dados anteriores são perdidos.