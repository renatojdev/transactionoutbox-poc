# 07 — Limitações e Decisões

Este documento registra as limitações conhecidas da integração `transaction-outbox` + Quarkus + jOOQ + MariaDB, o racional por trás das decisões tomadas na POC e as alternativas consideradas.

---

## 🎯 Decisão arquitetural

**Escolhemos `transaction-outbox` (gruelbox) como mecanismo de publicação de eventos pós-commit.**

A decisão foi tomada após comparar três abordagens:

1. **`transaction-outbox` (gruelbox)** — ✅ **Escolhida**
2. **Implementação manual** (`@Scheduled` + `SKIP LOCKED`)
3. **Debezium Embedded** (com outbox handler)

---

## 📊 Comparação das alternativas

| Critério | Manual | `transaction-outbox` | Debezium Embedded |
|:---|:---|:---|:---|
| **Código a escrever** | 300–500 linhas + testes | ~0 | ~0 |
| **Retry com backoff** | Manual | Pronto | Via Kafka Connect |
| **Idempotência** | Manual | Pronto (`uniqueRequestId`) | Manual |
| **Serialização de invocação** | Manual | Pronto | N/A (é CDC) |
| **Ordenação de eventos** | Manual | Pronta (`seq`) | N/A |
| **Footprint (RSS)** | ~5 MB | ~5–10 MB | **~350 MB** |
| **Native image** | Trivial | Requer registro de reflexão | ❌ Não compila |
| **Arestas de integração** | Nenhuma | 4 (ver doc 02) | Muitas |
| **Controle total** | Total | Alto | Baixo |
| **Complexidade de config** | Baixa | Média | Alta |

### Racional da escolha

- **Manual** foi descartada porque reimplementar retry, backoff, idempotência e ordenação é propenso a bugs sutis (race conditions, retry storm, deadlocks com `SKIP LOCKED` mal usado).
- **Debezium Embedded** foi descartada porque:
  - O footprint de ~350 MB não se justifica para publicação de eventos de domínio.
  - Não compila em GraalVM native (experiência prévia confirmada em outro projeto).
  - Carrega Kafka Connect, o que é desproporcional para o escopo.
- **`transaction-outbox`** venceu porque entrega o padrão pronto, com footprint baixo, compila em native (com ajuste) e tem apenas 4 arestas de integração, todas documentadas em [`02-integracoes-criticas.md`](./02-integracoes-criticas.md).

---

## ⚠️ Limitações conhecidas

### 1. Não é uma extensão Quarkus nativa

A lib foi projetada para ser framework-agnóstica. Isso significa que **ela não integra automaticamente com o CDI do Quarkus** como uma extensão nativa faria.

**Consequências**:

- É preciso `quarkus.index-dependency` para o CDI enxergar `QuarkusTransactionManager`.
- É preciso `@Unremovable` nos publishers, porque o Quarkus os remove por não detectar uso direto.
- Não há configuração via `application.properties` (tudo é configurado no builder do `OutboxConfig`).

**Impacto**: médio. São 3 ajustes de configuração, mas são "não-óbvios" e precisam ser documentados.

**Alternativa futura**: se a lib ganhar uma extensão Quarkus oficial (via Quarkiverse), esses ajustes desapareceriam.

---

### 2. `Instantiator` custom é obrigatório no Quarkus

A lib assume que `Class.forName` funciona com o TCCL da thread corrente. No Quarkus, as threads internas da lib (`ExecutorSubmitter`) **não têm** o `QuarkusClassLoader` como TCCL.

**Consequências**:

- O `CdiInstantiator.create()` fornecido pela lib **não funciona** em todos os cenários.
- É preciso um `Instantiator` custom que capture o classloader correto no momento da criação do bean.
- Esse `Instantiator` é uma solução **workaround**, não uma integração idiomática.

**Impacto**: médio. É ~30 linhas de código, mas quebra a "mágica" da lib e precisa ser mantido.

**Alternativa futura**: se a lib aceitar um `Instantiator` que respeite o TCCL em threads próprias, o código seria simplificado.

---

### 3. Native image requer registro de reflexão

A lib usa `Class.forName` para desserializar o payload JSON da invocação. O GraalVM, por padrão, remove classes acessadas apenas via reflexão.

**Consequências**:

- O binário **compila**, mas falha em runtime com `ClassNotFoundException` para arrays (`Byte[]`).
- É preciso registrar explicitamente os tipos usados: `@RegisterForReflection` ou `reflection-config.json`.
- Cada novo tipo de argumento no publisher pode exigir novo registro.

**Impacto**: baixo a médio. Uma vez registrado o conjunto de tipos, é estável. Mas o erro é obscuro e requer conhecimento de GraalVM.

**Alternativa futura**: se a lib incluir um `reflect-config.json` no JAR (padrão para libs GraalVM-friendly), o problema desapareceria.

---

### 4. WARN do Agroal é esperado

O `QuarkusTransactionManager` da lib obtém a conexão do `DataSource` e delega ao Narayana para fechá-la. O Agroal emite `WARN Closing open connection prior to commit` como medida defensiva.

**Consequências**:

- O log é poluído com WARN a cada ciclo do worker (a cada 5s).
- **Não é vazamento de conexão** — a conexão é fechada corretamente, apenas por outra camada.
- A solução paliativa é suprimir o log (`quarkus.log.category."io.agroal.pool".level=ERROR`).

**Impacto**: baixo (cosmético), mas é sintoma de integração imperfeita.

**Alternativa futura**: contribuir com a lib para registrar uma `Synchronization` no JTA que feche a conexão no `beforeCompletion`.

---

### 5. Dependência de retenção manual em produção

A lib remove automaticamente as linhas processadas após um `retentionTime` (default: 24h). Se esse tempo for muito curto ou o worker estiver parado, a tabela pode crescer indefinidamente.

**Consequências**:

- É preciso monitorar o tamanho da `TXNO_OUTBOX`.
- Eventos bloqueados **não são removidos** automaticamente e exigem intervenção manual.
- Não há métricas prontas — é preciso criar queries de monitoramento (ver [`06-operacao.md`](./06-operacao.md)).

**Impacto**: baixo, mas exige atenção operacional.

---

### 6. Sem suporte nativo a "dead letter queue" externa

Eventos bloqueados ficam na própria `TXNO_OUTBOX` com `blocked IS NOT NULL`. Não há mecanismo de mover para uma fila separada automaticamente.

**Consequências**:

- É preciso criar um job/manual para inspecionar e tratar eventos bloqueados.
- Não há integração com sistemas de DLQ (SQS, Kafka DLQ).

**Impacto**: baixo para a maioria dos casos, mas pode ser limitante em sistemas que exigem DLQ explícita.

**Alternativa**: implementar um listener via `TransactionOutboxListener` que, ao detectar bloqueio, move o evento para uma tabela separada.

---

### 7. Sem agrupamento por `topic` avançado

A coluna `topic` existe (default `*`), mas não há particionamento ou roteamento por tópico embutido.

**Consequências**:

- Se você quiser separar eventos por tipo/agrupamento, precisa definir o `topic` manualmente via `.schedule(...)`.
- Não há processamento paralelo por tópico por padrão.

**Impacto**: baixo, mas quem espera funcionalidade de "message broker" completo pode se decepcionar.

---

### 8. Documentação oficial é limitada para Quarkus

A doc oficial da lib é focada em:

- Spring Boot
- Micronaut
- Uso genérico (sem framework)

**Não cobre**:

- Integração com Quarkus (o `transactionoutbox-quarkus` não tem doc detalhada).
- Comportamento de `Class.forName` em classloaders isolados.
- Native image.

**Consequências**:

- O tempo de descoberta dos 4 pontos críticos foi significativo.
- Foi necessário criar este conjunto de documentos.

**Impacto**: médio, mitigado por esta documentação.

---

## 🎯 Quando a escolha **não** é adequada

A `transaction-outbox` pode não ser a melhor escolha se:

### 1. Você precisa de CDC de tabelas arbitrárias

Se o objetivo não é apenas publicar eventos de domínio, mas replicar mudanças de tabelas inteiras para outro sistema, o **Debezium** é a ferramenta certa. A `transaction-outbox` só lida com eventos explicitamente agendados.

### 2. Você tem latência sub-segundo como requisito

O worker roda a cada 5s (ou 1s se configurado). Para latência sub-segundo, um mecanismo baseado em CDC (Debezium) ou trigger de banco é mais adequado.

### 3. Você não quer dependência de terceiros

Se a política do projeto é "zero dependências não-críticas", a implementação manual é preferível, mesmo com o custo de manutenção.

### 4. Native image sem ajuste manual é requisito

Se você precisa que a lib funcione em native "out of the box", sem registro de reflexão, ela não atende. É preciso aceitar o ajuste.

### 5. Você quer transações distribuídas (XA)

A `transaction-outbox` usa transações locais (JTA). Se você precisa de XA entre múltiplos recursos, é preciso outra abordagem.

---

## 🚫 O que foi descartado e por quê

### Debezium Embedded

**Descartado porque**:

- Footprint de ~350 MB em produção (medido em projeto real).
- **Não compila** em GraalVM native (confirmado na prática).
- Carrega Kafka Connect completo apenas para publicar eventos de uma tabela.
- Sem message relay: seria preciso implementar retry/DLQ manualmente.
- Complexidade de configuração (dezenas de propriedades).

### Implementação manual

**Descartada porque**:

- Reimplementar retry, backoff, idempotência, lock otimista e ordenação é propenso a bugs.
- A `transaction-outbox` já resolve tudo isso, com 7+ anos de uso em produção por outros times.
- O tempo economizado não compensa a manutenção contínua.

### Outras bibliotecas

- **Ekbatan**: considerada, tem suporte nativo a Quarkus + jOOQ, mas é um framework de persistência mais invasivo (muda o modelo de entidades).
- **Charks Outbox**: opção válida, mas menos madura que a gruelbox.

---

## 🧭 Decisões técnicas registradas

### Por que `Dialect.MY_SQL_8` e não `Dialect.MARIADB`?

A enum `Dialect` da lib não tem uma constante `MARIADB` específica. `MY_SQL_8` é usado porque:

- MariaDB 10.6+ é compatível com MySQL 8 em sintaxe e recursos (`SKIP LOCKED`).
- A `Dialect` da lib só determina se o `SKIP LOCKED` é suportado.
- É a escolha padrão documentada.

### Por que não usar `CdiInstantiator.create()`?

Porque ele delega ao `CDI.current().select(clazz).get()`, que usa o TCCL da thread corrente. Em threads internas da lib, isso falha.

### Por que `@Scheduled` em vez de thread manual?

Porque o Quarkus gerencia o ciclo de vida, aplica CDI corretamente e roda em thread com TCCL correto. Thread manual exige configuração explícita de classloader.

### Por que `QuarkusTransaction.requiringNew()` no worker?

Porque o `flush()` precisa de uma transação ativa para executar as queries de leitura/update na `TXNO_OUTBOX`. Sem isso, o `QuarkusTransactionManager` reclama ou cria transações implícitas com problemas.

### Por que suprimir o log do Agroal?

Porque o WARN é benigno e polui o log a cada 5s. A causa raiz é uma imperfeição da lib, e a solução definitiva (contribuir com PR) não é viável no curto prazo.

---

## 📈 O que monitorar no longo prazo

Se a `transaction-outbox` for adotada em produção, revise periodicamente:

| Item | Frequência | Ação se degradar |
|:---|:---|:---|
| Footprint do processo | Mensal | Comparar com baseline |
| Tamanho da `TXNO_OUTBOX` | Semanal | Ajustar `retentionTime` |
| Eventos bloqueados | Diário | Investigar publisher |
| Tempo médio de processamento | Mensal | Ajustar `submitBatchSize` |
| Novas versões da lib | Trimestral | Avaliar upgrade |

---

## 🔮 Roadmap de melhorias (se aplicável)

Se o time decidir investir na integração:

### Curto prazo

- [ ] Adicionar métricas Prometheus para a outbox.
- [ ] Criar health check (`/q/health/outbox`).
- [ ] Configurar alertas para eventos bloqueados.

### Médio prazo

- [ ] Abrir PR na lib para:
  - Incluir `reflect-config.json` no JAR (native image).
  - Registrar `Synchronization` no JTA para fechar conexão antes do commit.
- [ ] Publicar um guia de integração como o deste repositório em um blog/issue.

### Longo prazo

- [ ] Avaliar extensão Quarkus oficial da lib (se surgir).
- [ ] Considerar `Ekbatan` se o projeto evoluir para um framework de persistência mais completo.

---

## 📌 Resumo executivo

| Aspecto | Avaliação |
|:---|:---|
| **Funciona?** | ✅ Sim, validado em POC |
| **Footprint** | ✅ ~5–10 MB (muito menor que Debezium) |
| **Native image** | ⚠️ Compila, requer registro de reflexão |
| **Arestas de integração** | ⚠️ 4, todas documentadas |
| **Manutenção** | ✅ Baixa (a lib cuida do pesado) |
| **Controle** | ✅ Alto (você controla o worker e o publisher) |
| **Alternativas melhores?** | ❌ Para o escopo, não |

**Recomendação**: adotar em produção, desde que:

1. Os 4 pontos críticos estejam configurados (ver [`02-integracoes-criticas.md`](./02-integracoes-criticas.md)).
2. O monitoramento de eventos bloqueados esteja em vigor.
3. O build nativo seja validado com os tipos de argumento usados.

---

**Anterior**: [`06-operacao.md`](./06-operacao.md)
**Voltar ao início**: [`00-visao-geral.md`](./00-visao-geral.md)
