# 06 — Operação

Este documento cobre os aspectos operacionais do padrão Outbox em produção: schema da tabela, monitoramento, tuning, manutenção e alertas.

---

## 📊 Schema da tabela `TXNO_OUTBOX`

A tabela é criada automaticamente pela lib no primeiro `flush()`. O schema pode evoluir entre versões, e a lib aplica migrações incrementais automaticamente via `DefaultMigrationManager`.

### Schema atual (versão 7.1.769)

```sql
CREATE TABLE TXNO_OUTBOX (
  id              varchar(36)  NOT NULL PRIMARY KEY,
  invocation      mediumtext   NULL,
  lastAttemptTime timestamp(6) NULL,
  nextAttemptTime timestamp(6) NULL,
  attempts        int(11)      NULL,
  blocked         varchar(250) NULL,
  version         int(11)      NULL,
  uniqueRequestId varchar(250) NULL UNIQUE,
  processed       tinyint(1)   NULL,
  topic           varchar(250) NOT NULL DEFAULT '*',
  seq             bigint(20)   NULL
);
```

### Descrição das colunas

| Coluna | Tipo | Função |
|:---|:---|:---|
| `id` | varchar(36) | UUID único do evento |
| `invocation` | mediumtext | JSON com a chamada do método agendado (`className`, `methodName`, `args`) |
| `lastAttemptTime` | timestamp(6) | Momento da última tentativa de processamento |
| `nextAttemptTime` | timestamp(6) | Quando a lib vai tentar processar novamente (backoff exponencial) |
| `attempts` | int(11) | Contador de tentativas já realizadas |
| `blocked` | varchar(250) | Motivo do bloqueio após esgotar tentativas (NULL se não bloqueado) |
| `version` | int(11) | Lock otimista para evitar processamento concorrente |
| `uniqueRequestId` | varchar(250) | Identificador para idempotência (evita duplicação) |
| `processed` | tinyint(1) | `0` = pendente, `1` = processado |
| `topic` | varchar(250) | Agrupamento lógico (default: `*`) |
| `seq` | bigint(20) | Ordenação de eventos |

> **Nota**: a lib **remove automaticamente** as linhas processadas após um tempo (configurável). Não é necessário limpar manualmente em operação normal.

### Índices criados

```sql
CREATE INDEX IX_TXNO_OUTBOX_1 ON TXNO_OUTBOX (processed, blacklisted, nextAttemptTime);
```

O índice cobre a query principal do `flush()`:

```sql
SELECT * FROM TXNO_OUTBOX
WHERE processed = false
  AND blocked IS NULL
  AND nextAttemptTime <= NOW(6)
ORDER BY nextAttemptTime ASC
LIMIT ?
FOR UPDATE SKIP LOCKED;
```

---

## 🔍 Monitoramento

### Queries de diagnóstico

#### 1. Quantos eventos pendentes?

```sql
SELECT COUNT(*) AS pendentes
FROM TXNO_OUTBOX
WHERE processed = false
  AND blocked IS NULL;
```

**Interpretação**: se esse número cresce continuamente, o worker não está processando rápido o suficiente, ou está parado.

#### 2. Quantos eventos bloqueados?

```sql
SELECT COUNT(*) AS bloqueados
FROM TXNO_OUTBOX
WHERE blocked IS NOT NULL;
```

**Interpretação**: eventos que esgotaram as retentativas e desistiram. **Requer atenção manual** — o publisher está falhando de forma persistente.

#### 3. Eventos por tentativa

```sql
SELECT attempts, COUNT(*) AS qtd
FROM TXNO_OUTBOX
WHERE processed = false
GROUP BY attempts
ORDER BY attempts;
```

**Interpretação**: se a maioria dos eventos tem `attempts = 0`, está tudo normal. Se há muitos com `attempts > 3`, algo está falhando no publisher.

#### 4. Últimos eventos processados

```sql
SELECT id, attempts, lastAttemptTime, nextAttemptTime
FROM TXNO_OUTBOX
WHERE processed = true
ORDER BY lastAttemptTime DESC
LIMIT 10;
```

**Interpretação**: confirma que o worker está rodando e processando eventos recentemente.

#### 5. Detalhes de eventos bloqueados

```sql
SELECT id, attempts, blocked, LEFT(invocation, 300) AS inv
FROM TXNO_OUTBOX
WHERE blocked IS NOT NULL
ORDER BY lastAttemptTime DESC;
```

**Interpretação**: mostra o motivo do bloqueio e a invocação que falhou. Útil para debug.

### Métricas recomendadas

Se você usa Micrometer/Prometheus, exponha estas métricas:

| Métrica | Como calcular | Alerta |
|:---|:---|:---|
| `outbox_pending_count` | `COUNT(*) WHERE processed = false AND blocked IS NULL` | > 100 por mais de 5 min |
| `outbox_blocked_count` | `COUNT(*) WHERE blocked IS NOT NULL` | > 0 |
| `outbox_oldest_pending_age_seconds` | `TIMESTAMPDIFF(SECOND, MIN(nextAttemptTime), NOW())` | > 60 |
| `outbox_processed_total` | `COUNT(*) WHERE processed = true` (incremento) | — |
| `outbox_attempts_avg` | `AVG(attempts) WHERE processed = false` | > 2 |

### Exemplo de endpoint de health check

```java
@Path("/q/health/outbox")
@ApplicationScoped
public class OutboxHealthCheck implements HealthCheck {

    @Inject
    DSLContext dsl;

    @Override
    public HealthCheckResponse call() {
        int pendentes = dsl.fetchCount(
                DSL.table("TXNO_OUTBOX"),
                DSL.field("processed").eq(false),
                DSL.field("blocked").isNull());

        int bloqueados = dsl.fetchCount(
                DSL.table("TXNO_OUTBOX"),
                DSL.field("blocked").isNotNull());

        HealthCheckResponseBuilder builder = HealthCheckResponse.named("outbox")
                .withData("pendentes", pendentes)
                .withData("bloqueados", bloqueados);

        if (bloqueados > 0) {
            return builder.down().build();
        }
        if (pendentes > 100) {
            return builder.down().withData("motivo", "fila muito grande").build();
        }
        return builder.up().build();
    }
}
```

---

## ⚙️ Tuning do worker

### Intervalo do `@Scheduled`

O intervalo define a latência entre o commit e a publicação:

```java
@Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
```

| Intervalo | Latência | Custo |
|:---|:---|:---|
| 1s | Baixa (~1s) | Query a cada segundo |
| 5s | Média (~5s) | Query a cada 5s (padrão) |
| 30s | Alta | Query a cada 30s |

> **Recomendação**: 5s é um bom equilíbrio. Para latência crítica, use 1s. Para eventos esporádicos, 30s.

### Tamanho do lote

A lib processa em lotes. O tamanho default é 10. Para ajustar, use `.submitBatchSize()` no builder:

```java
return TransactionOutbox.builder()
        .instantiator(instantiator)
        .transactionManager(transactionManager)
        .persistor(Persistor.forDialect(Dialect.MY_SQL_8))
        .submitBatchSize(50)  // processa até 50 eventos por ciclo
        .build();
```

**Trade-off**: lotes maiores aumentam o throughput mas prolongam o tempo de cada `flush()`. Se o worker demora mais que o intervalo, o `SKIP` evita sobreposição, mas a latência aumenta.

### Número de threads

O `flush()` processa os eventos em paralelo internamente, usando um `ExecutorSubmitter`. O número de threads pode ser configurado:

```java
return TransactionOutbox.builder()
        .instantiator(instantiator)
        .transactionManager(transactionManager)
        .persistor(Persistor.forDialect(Dialect.MY_SQL_8))
        .submitter(new ExecutorSubmitter(Executors.newFixedThreadPool(4)))
        .build();
```

> **Nota**: em geral, o default (1 thread por evento, com pool) é suficiente. Aumente apenas se o publisher for I/O-bound (ex: chamadas HTTP longas).

---

## 🗄️ Tuning do MariaDB

### `SKIP LOCKED` (requisito)

A lib usa `SELECT ... FOR UPDATE SKIP LOCKED` para pegar um lote de eventos sem bloquear outras instâncias. **Isso exige MariaDB 10.6+** com engine InnoDB.

```sql
SELECT VERSION();
-- Esperado: 10.6.x ou superior
```

### Índices

O índice `IX_TXNO_OUTBOX_1` cobre a query principal. Se a tabela crescer muito e as queries ficarem lentas, verifique:

```sql
EXPLAIN SELECT * FROM TXNO_OUTBOX
WHERE processed = false
  AND blocked IS NULL
  AND nextAttemptTime <= NOW(6)
ORDER BY nextAttemptTime ASC
LIMIT 10
FOR UPDATE SKIP LOCKED;
```

**Esperado**: `type: range`, `key: IX_TXNO_OUTBOX_1`.

### Limpeza automática

A lib remove automaticamente as linhas processadas após um tempo. O threshold é configurável:

```java
return TransactionOutbox.builder()
        // ...
        .persistor(Persistor.forDialect(Dialect.MY_SQL_8))
        .retentionTime(Duration.ofHours(24))  // mantém 24h de histórico
        .build();
```

Se a tabela crescer indefinidamente, verifique se a retenção está configurada.

### Configuração do pool

O pool do Agroal precisa de conexões suficientes para o worker + requisições HTTP:

```properties
quarkus.datasource.jdbc.min-size=2
quarkus.datasource.jdbc.max-size=10
quarkus.datasource.jdbc.acquisition-timeout=5
```

**Sintoma de pool pequeno**: erros `SQLException: Connection is not available` no worker ou nas requisições.

---

## 🧹 Manutenção

### Limpar eventos bloqueados

Eventos bloqueados exigem intervenção manual. Para removê-los:

```sql
-- Ver o que está bloqueado antes
SELECT id, attempts, blocked, invocation
FROM TXNO_OUTBOX
WHERE blocked IS NOT NULL;

-- Remover (após análise)
DELETE FROM TXNO_OUTBOX WHERE blocked IS NOT NULL;
```

**Antes de deletar**, investigue a causa do bloqueio. Pode ser:

- Bug no publisher (ex: `NullPointerException`).
- Problema de infraestrutura (ex: broker fora do ar por muito tempo).
- Dados inválidos no `invocation` (ex: classe removida).

### Reprocessar eventos bloqueados

Se você corrigiu a causa, pode desbloquear os eventos:

```sql
UPDATE TXNO_OUTBOX
SET blocked = NULL,
    attempts = 0,
    nextAttemptTime = NOW(6)
WHERE blocked IS NOT NULL;
```

O worker vai pegar os eventos no próximo ciclo.

### Reset completo (apenas em desenvolvimento)

```sql
TRUNCATE TABLE TXNO_OUTBOX;
TRUNCATE TABLE person;
```

> **Nunca** faça isso em produção sem backup. A `TXNO_OUTBOX` contém eventos que ainda não foram publicados.

---

## 🚨 Alertas recomendados

| Alerta | Condição | Severidade | Ação |
|:---|:---|:---|:---|
| `OutboxBlocked` | `COUNT(*) WHERE blocked IS NOT NULL > 0` | **Crítica** | Investigar publisher |
| `OutboxPendingHigh` | `COUNT(*) WHERE processed = false > 100` | **Alta** | Verificar worker |
| `OutboxOldestPending` | `TIMESTAMPDIFF(MINUTE, MIN(nextAttemptTime), NOW()) > 5` | **Alta** | Worker pode estar parado |
| `OutboxTableGrowing` | `COUNT(*) > 10000` | **Média** | Verificar retenção/limpeza |
| `OutboxWorkerStopped` | Nenhum `processed = true` nos últimos 10 min | **Crítica** | Verificar scheduler |

### Script de alerta (exemplo para cron)

```sql
SELECT
  COUNT(*) AS pendentes,
  SUM(CASE WHEN blocked IS NOT NULL THEN 1 ELSE 0 END) AS bloqueados,
  MAX(CASE WHEN processed = false THEN TIMESTAMPDIFF(SECOND, nextAttemptTime, NOW()) ELSE 0 END) AS segundos_atraso
FROM TXNO_OUTBOX;
```

Se `bloqueados > 0` ou `segundos_atraso > 60`, dispare alerta.

---

## 📈 Capacidade

### Quantos eventos por segundo?

Com worker a cada 5s e lote de 50:

```
Throughput máximo = 50 eventos / 5s = 10 eventos/segundo
```

Para aumentar:

1. Reduza o intervalo (`every = "1s"`).
2. Aumente o lote (`submitBatchSize(200)`).
3. Aumente as threads do `ExecutorSubmitter`.

### Limite prático

| Cenário | Throughput estimado |
|:---|:---|
| Default (5s, 10/lote, 1 thread) | ~2 eventos/s |
| Tuning leve (1s, 50/lote, 4 threads) | ~200 eventos/s |
| Tuning agressivo (1s, 200/lote, 8 threads) | ~1600 eventos/s |

> **Atenção**: o `SKIP LOCKED` limita a concorrência por design. Acima de um certo ponto, o MariaDB vira o gargalo.

---

## 🧭 Checklist operacional

### Diário

- [ ] `outbox_blocked_count == 0`
- [ ] `outbox_pending_count < 100`
- [ ] Worker processando (`outbox_processed_total` incrementando)

### Semanal

- [ ] Tamanho da `TXNO_OUTBOX` estável (< 10000 linhas)
- [ ] `attempts` médio baixo (< 2)
- [ ] Nenhum evento com `nextAttemptTime` muito antigo

### Mensal

- [ ] Verificar versão do MariaDB (>= 10.6)
- [ ] Revisar configuração de retenção
- [ ] Revisar logs de erro do worker

---

## 📌 Resumo

| Aspecto | Padrão | Recomendação |
|:---|:---|:---|
| **Intervalo do worker** | 5s | 5s (dev) / 1s (prod crítico) |
| **Lote** | 10 | 50–200 |
| **Retenção** | 24h | 24–72h |
| **Índice** | `IX_TXNO_OUTBOX_1` | Não remover |
| **Alerta bloqueados** | > 0 | Crítico |
| **Alerta pendentes** | > 100 | Alta prioridade |

---

**Anterior**: [`05-diagnostico.md`](./05-diagnostico.md)
**Próximo**: [`07-limitacoes.md`](./07-limitacoes.md)
