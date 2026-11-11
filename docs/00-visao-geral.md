# Visão Geral — Transactional Outbox com Quarkus + jOOQ + MariaDB

Este diretório documenta a integração da biblioteca [`transaction-outbox` (gruelbox)](https://github.com/gruelbox/transaction-outbox) com **Quarkus 3.x**, **jOOQ** e **MariaDB 10.6+**, conforme validado em prova de conceito.

O objetivo é registrar decisões, armadilhas e soluções para que o time não precise redescobrir os mesmos problemas.

---

## 🎯 Por que Transactional Outbox?

Ao publicar eventos de domínio (Kafka, Event Bus, webhooks, e-mail) a partir de uma operação de banco, surgem dois problemas clássicos:

1. **Evento perdido**: o dado é commitado, mas a publicação falha (rede, broker fora do ar) → dado e evento ficam inconsistentes.
2. **Evento fantasma**: o evento é publicado, mas o commit da transação falha → o consumidor reage a um dado que não existe.

O padrão **Transactional Outbox** resolve ambos: o evento é persistido **na mesma transação** do dado de negócio. Depois do commit, um worker lê a tabela de outbox e publica o evento, com retentativas automáticas.

```
┌─────────────────────────────────────────────────────────────┐
│                     Transação única                          │
│  ┌──────────────┐    ┌────────────────────────┐             │
│  │ INSERT dado  │    │ INSERT evento (outbox) │             │
│  └──────────────┘    └────────────────────────┘             │
│                          ↓                                   │
│                       COMMIT                                 │
└─────────────────────────────────────────────────────────────┘
                           ↓
              ┌────────────────────────┐
              │  Worker lê outbox      │
              │  Publica evento        │
              │  Marca como processado │
              └────────────────────────┘
```

---

## 🏗️ Stack validada

| Componente | Versão | Papel |
|:---|:---|:---|
| **Quarkus** | 3.40.1 | Runtime da aplicação |
| **jOOQ** | 3.19+ | Acesso a dados |
| **MariaDB** | 10.6+ | Banco (requisito: `SKIP LOCKED`) |
| **transaction-outbox** | 7.1.769 | Biblioteca do padrão Outbox |
| **Narayana JTA** | (bundled) | Gerenciador de transações |
| **Agroal** | (bundled) | Pool de conexões |

> ⚠️ **MariaDB < 10.6** não suporta `SELECT ... FOR UPDATE SKIP LOCKED`. A lib ainda funciona, mas o `flush()` perde a capacidade de processamento concorrente.

---

## 🧭 Como navegar esta documentação

| Documento | Conteúdo |
|:---|:---|
| [`00-visao-geral.md`](./00-visao-geral.md) | Este arquivo. Contexto, motivação e stack. |
| [`01-setup.md`](./01-setup.md) | Dependências, `application.properties` e estrutura de código. |
| [`02-integracoes-criticas.md`](./02-integracoes-criticas.md) | Os 4 pontos críticos que a doc oficial da lib não cobre. |
| [`03-testes.md`](./03-testes.md) | Testes de atomicidade e de publicação pós-commit. |
| [`04-native-image.md`](./04-native-image.md) | Build nativo, registro de reflexão e limitações. |
| [`05-diagnostico.md`](./05-diagnostico.md) | Checklist de sintomas, causas e soluções. |
| [`06-operacao.md`](./06-operacao.md) | Schema da tabela, monitoramento e tuning. |
| [`07-limitacoes.md`](./07-limitacoes.md) | Limitações conhecidas e decisões arquiteturais. |

---

## 🧩 Estrutura da aplicação

```
src/main/java/org/acme/
├── OutboxConfig.java           # Produz o bean TransactionOutbox
├── OutboxWorker.java           # @Scheduled que chama outbox.flush()
├── MyService.java              # Chama outbox.schedule() dentro do @Transactional
├── PessoaPublisher.java        # Interface do publisher
├── PessoaPublisherImpl.java    # Implementação (com @Unremovable)
└── PessoaResource.java         # REST para testar
```

O fluxo de uma requisição é:

```
POST /pessoas
    │
    ▼
PessoaResource.criar()
    │
    ▼
MyService.criarPessoa()  ← @Transactional
    │
    ├─ INSERT em person
    │
    └─ outbox.schedule(PessoaPublisherImpl.class).publicarPessoaCriada(...)
                                  │
                                  └─ INSERT em TXNO_OUTBOX (mesma transação)
    │
    ▼
COMMIT
    │
    ▼
OutboxWorker.runWorker()  ← @Scheduled(every = "5s")
    │
    ├─ outbox.flush()
    │       │
    │       └─ lê TXNO_OUTBOX, chama PessoaPublisherImpl.publicarPessoaCriada(...)
    │
    └─ marca registro como processado
```

---

## ✅ O que foi validado na POC

| Item | Status |
|:---|:---|
| Atomicidade (rollback limpa `person` **e** `TXNO_OUTBOX`) | ✅ Testado |
| Publicação pós-commit | ✅ Testado |
| MariaDB 12.1.2 com `SKIP LOCKED` | ✅ Funcionando |
| Retry automático em falha do publisher | ✅ Confirmado (`attempts` incrementa) |
| Build native (GraalVM) | ⚠️ Compila; requer registro de reflexão |
| Footprint em produção | ⏳ A medir |

---

## ⚖️ Comparação com alternativas

| Critério | Implementação manual (`@Scheduled` + `SKIP LOCKED`) | `transaction-outbox` | Debezium Embedded |
|:---|:---|:---|:---|
| **Código a escrever** | 300–500 linhas + testes | ~0 | ~0 |
| **Retry com backoff** | Manual | Pronto | Via Kafka Connect |
| **Idempotência** | Manual | Pronto | Manual |
| **Serialização de invocação** | Manual | Pronto | N/A (é CDC) |
| **Footprint (RSS)** | ~5 MB | ~5–10 MB | ~350 MB |
| **Native image** | Trivial | Requer reflexão | ❌ Não compila |
| **Arestas de integração** | Nenhuma (é seu código) | 4 (ver doc 02) | Muitas |
| **Controle total** | Total | Alto | Baixo |

> **Decisão**: `transaction-outbox` escolhido por entregar o padrão pronto com arestas gerenciáveis. Ver [`07-limitacoes.md`](./07-limitacoes.md) para o racional completo.

---

## 🚀 Como começar

1. Leia [`01-setup.md`](./01-setup.md) para configurar dependências e beans.
2. Leia [`02-integracoes-criticas.md`](./02-integracoes-criticas.md) **antes** de rodar — os 4 pontos ali são obrigatórios no Quarkus.
3. Siga [`03-testes.md`](./03-testes.md) para validar atomicidade.
4. Em caso de erro, consulte [`05-diagnostico.md`](./05-diagnostico.md).

---

## 📌 Referências externas

- [transaction-outbox no GitHub](https://github.com/gruelbox/transaction-outbox)
- [README oficial da lib](https://github.com/gruelbox/transaction-outbox/blob/master/README.md)
- [Quarkus — CDI Reference](https://quarkus.io/guides/cdi-reference)
- [Quarkus — Native Image](https://quarkus.io/guides/building-native-image)
- [GraalVM — Reflection configuration](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- [MariaDB — SKIP LOCKED](https://mariadb.com/kb/en/select/#skip-locked)
- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)

---

**Próximo**: [`01-setup.md`](./01-setup.md) →
