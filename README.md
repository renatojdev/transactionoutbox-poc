# Guia de Integração: `transaction-outbox` (gruelbox) + Quarkus + jOOQ + MariaDB

Este guia documenta a integração da biblioteca [`transaction-outbox`](https://github.com/gruelbox/transaction-outbox) com **Quarkus 3.x**, **jOOQ** e **MariaDB 10.6+**.

A doc oficial da biblioteca **não cobre** vários pontos específicos do Quarkus. Este documento existe para que você (ou qualquer pessoa do time) não precise redescobrir esses detalhes.
---

***Para maiores detalhes. Veja a pasta `/docs` deste projeto. [`00-visao-geral.md`](./docs/00-visao-geral.md) →***

## 📋 Pré-requisitos

| Componente | Versão mínima | Motivo |
|:---|:---|:---|
| **Quarkus** | 3.x | Suporte a `@Scheduled`, `QuarkusTransaction`, Arc |
| **MariaDB** | **10.6** | Suporte a `SELECT ... FOR UPDATE SKIP LOCKED`, exigido pela lib para concorrência |
| **jOOQ** | 3.19+ | Compatibilidade com dialeto MariaDB |
| **transaction-outbox** | 7.1.769 | Versão testada nesta POC |

> ⚠️ **MariaDB < 10.6**: a lib ainda funciona, mas o `flush()` pode causar erros de lock em ambientes com múltiplas instâncias. Nesse caso, garanta que apenas **uma instância** execute o worker.

---

## 📦 Dependências (pom.xml)

```xml
<!-- Extensão jOOQ do Quarkiverse (opcional; veja seção "Sem a extensão quarkus-jooq") -->
<!-- <dependency>...</dependency> -->

<!-- Driver JDBC (transitivo via quarkus-jdbc-mariadb, não precisa declarar) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-mariadb</artifactId>
</dependency>

<!-- JTA (necessário para @Transactional e QuarkusTransactionManager) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-narayana-jta</artifactId>
</dependency>

<!-- Scheduler (necessário para o @Scheduled do worker) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-scheduler</artifactId>
</dependency>

<!-- jOOQ core (a extensão quarkus-jooq trazia, mas foi removida por incompatibilidade com Quarkus 3.40+) -->
<dependency>
    <groupId>org.jooq</groupId>
    <artifactId>jooq</artifactId>
    <version>3.21.9</version>
</dependency>

<!-- Transaction Outbox -->
<dependency>
    <groupId>com.gruelbox</groupId>
    <artifactId>transactionoutbox-quarkus</artifactId>
    <version>7.1.785</version>
</dependency>
<dependency>
    <groupId>com.gruelbox</groupId>
    <artifactId>transactionoutbox-jooq</artifactId>
    <version>7.1.785</version>
</dependency>
```

---

## ⚙️ Configuração (`application.properties`)

```properties
quarkus.http.port=8081
quarkus.datasource.db-kind=mariadb
quarkus.datasource.jdbc.url=jdbc:mariadb://localhost:3306/outboxtx
quarkus.datasource.username=seu_usuario
quarkus.datasource.password=sua_senha

# ⚠️ OBRIGATÓRIO: indexar a lib para o CDI encontrar QuarkusTransactionManager
quarkus.index-dependency.transactionoutbox.group-id=com.gruelbox
quarkus.index-dependency.transactionoutbox.artifact-id=transactionoutbox-quarkus

# Paliativo: suprime WARN do Agroal ("Closing open connection prior to commit")
# O aviso é benigno (Agroal fecha a conexão por conta própria), mas polui o log.
quarkus.log.category."io.agroal.pool".level=ERROR
```

---

## 🔑 Os 4 pontos críticos (o que a doc oficial não conta)

### 1. `quarkus.index-dependency` — sem isso, o CDI não vê a lib

**Sintoma**: `UnsatisfiedResolutionException: Unsatisfied dependency for type QuarkusTransactionManager`

**Causa**: a `transactionoutbox-quarkus` não inclui índice Jandex. Sem ele, o Quarkus não descobre a classe `QuarkusTransactionManager` como bean CDI.

**Solução**: as duas linhas de `quarkus.index-dependency` no `application.properties`.

---

### 2. `Instantiator` custom — sem isso, `ClassNotFoundException` no worker

**Sintoma**: `ClassNotFoundException: org.acme.PessoaPublisherImpl` no log do worker.

**Causa**: o `flush()` da lib processa os eventos em threads internas (`ExecutorSubmitter`, `pool-X-thread-Y`). Essas threads **não têm o `QuarkusClassLoader` como TCCL**. O `Class.forName` do `AbstractFullyQualifiedNameInstantiator` falha.

**Solução**: um `Instantiator` custom que captura o classloader correto **no momento da criação do bean** (thread do Quarkus) e o usa explicitamente no `Class.forName`.

```java
ClassLoader quarkusClassLoader = Thread.currentThread().getContextClassLoader();

Instantiator instantiator = new Instantiator() {
    @Override
    public String getName(Class<?> clazz) {
        return clazz.getName();
    }

    @Override
    public Object getInstance(String className) {
        try {
            Class<?> clazz = Class.forName(className, true, quarkusClassLoader);
            InstanceHandle<?> handle = Arc.container().instance(clazz);
            if (handle.isAvailable()) {
                return handle.get();
            }
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao instanciar via Quarkus CDI: " + className, e);
        }
    }
};
```

> **Não substitua por `CdiInstantiator.create()`**. Funciona no `@QuarkusTest`, mas quebra no dev mode com o worker em thread separada.

---

### 3. `@Unremovable` no publisher — sem isso, o bean é removido no build

**Sintoma**: warning do CDI `At least one bean matched the required type and qualifiers but was marked as unused and removed during build` seguido de `NoSuchMethodException`.

**Causa**: o Quarkus remove beans "não usados" no build. Como o publisher é resolvido programaticamente (via `Arc.container().instance(clazz)`), o Quarkus não "vê" a dependência.

**Solução**: anotar a implementação do publisher.

```java
@Unremovable
@ApplicationScoped
public class PessoaPublisherImpl implements PessoaPublisher {
    // ...
}
```

---

### 4. Supressão de log do Agroal — paliativo conhecido

**Sintoma**: `WARN [io.agroal.pool] Datasource '<default>': Closing 1 open connection(s) prior to commit`

**Causa**: o `QuarkusTransactionManager` da lib obtém a conexão do `DataSource` e delega ao Narayana para fechá-la. O Agroal emite um aviso preventivo antes do commit. **Não é vazamento de conexão.**

**Solução paliativa**: `quarkus.log.category."io.agroal.pool".level=ERROR`.

**Solução definitiva**: contribuir com a lib para que o `QuarkusTransactionManager` registre uma `Synchronization` que feche a conexão antes do commit.

---

## 🏗️ Estrutura de código

```
src/main/java/org/acme/
├── OutboxConfig.java           # Produz o bean TransactionOutbox
├── OutboxWorker.java           # @Scheduled que chama outbox.flush()
├── MyService.java              # Chama outbox.schedule() dentro do @Transactional
├── PessoaPublisher.java        # Interface do publisher
├── PessoaPublisherImpl.java    # Implementação (com @Unremovable)
├── PessoaResource.java         # REST para testar
└── JooqProducer.java           # Produz o DSLContext manualmente (se sem quarkus-jooq)
```

### `OutboxConfig.java`

```java
package org.acme;

import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.Instantiator;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.quarkus.QuarkusTransactionManager;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class OutboxConfig {

    @Produces
    @ApplicationScoped
    public TransactionOutbox transactionOutbox(QuarkusTransactionManager transactionManager) {

        ClassLoader quarkusClassLoader = Thread.currentThread().getContextClassLoader();

        Instantiator instantiator = new Instantiator() {
            @Override
            public String getName(Class<?> clazz) {
                return clazz.getName();
            }

            @Override
            public Object getInstance(String className) {
                try {
                    Class<?> clazz = Class.forName(className, true, quarkusClassLoader);
                    InstanceHandle<?> handle = Arc.container().instance(clazz);
                    return handle.isAvailable()
                            ? handle.get()
                            : clazz.getDeclaredConstructor().newInstance();
                } catch (Exception e) {
                    throw new RuntimeException("Erro ao instanciar via Quarkus CDI: " + className, e);
                }
            }
        };

        return TransactionOutbox.builder()
                .instantiator(instantiator)
                .transactionManager(transactionManager)
                .persistor(Persistor.forDialect(Dialect.MY_SQL_8))
                .build();
    }
}
```

> **`Dialect.MY_SQL_8`** (com underscores), não `MYSQL_8`. A enum `Dialect` da lib é separada da `SQLDialect` do jOOQ.

### `OutboxWorker.java`

```java
package org.acme;

import com.gruelbox.transactionoutbox.TransactionOutbox;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class OutboxWorker {

    private static final Logger LOG = Logger.getLogger(OutboxWorker.class);

    @Inject
    TransactionOutbox outbox;

    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void runWorker() {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                while (outbox.flush()) {
                    // processando...
                }
            });
        } catch (Exception e) {
            LOG.error("Erro ao processar a outbox.", e);
        }
    }
}
```

### `MyService.java`

```java
@ApplicationScoped
public class MyService {

    @Inject
    DSLContext dsl;

    @Inject
    TransactionOutbox outbox;

    @Transactional
    public PersonRecord criarPessoa(String firstName, String lastName,
                                    String email, LocalDate birthDate,
                                    String phoneNumber) {

        PersonRecord person = dsl.insertInto(Person.PERSON)
                .columns(Person.PERSON.FIRST_NAME,
                         Person.PERSON.LAST_NAME,
                         Person.PERSON.EMAIL,
                         Person.PERSON.BIRTH_DATE,
                         Person.PERSON.PHONE_NUMBER)
                .values(firstName, lastName, email, birthDate, phoneNumber)
                .returning()
                .fetchOne();

        outbox.schedule(PessoaPublisherImpl.class)
              .publicarPessoaCriada(person.getPersonId(), firstName, lastName, email);

        return person;
    }
}
```

> Pode agendar sobre a **interface** (`PessoaPublisher.class`) ou a **classe concreta** (`PessoaPublisherImpl.class`). Ambas funcionam **desde que o `Instantiator` custom esteja configurado**. Sem ele, somente a classe concreta resolve — e mesmo assim de forma frágil.

### `PessoaPublisherImpl.java`

```java
@Unremovable
@ApplicationScoped
public class PessoaPublisherImpl implements PessoaPublisher {

    private static final Logger LOG = Logger.getLogger(PessoaPublisherImpl.class);

    @Override
    public void publicarPessoaCriada(Integer personId, String firstName,
                                     String lastName, String email) {
        LOG.infof("Publicando evento pós-commit: personId=%d, nome=%s %s, email=%s",
                  personId, firstName, lastName, email);
        // Publique no Event Bus do Vert.x, Kafka, e-mail, etc.
    }
}
```

---

## 🧪 Teste de atomicidade

```java
@QuarkusTest
class OutboxAtomicidadeTest {

    @Inject MyService servico;
    @Inject DSLContext dsl;

    @Test
    void eventoNaoDeveSerPersistidoSeTransacaoFalhar() {
        int pessoasAntes = dsl.fetchCount(Person.PERSON);
        int outboxAntes  = dsl.fetchCount(DSL.table("TXNO_OUTBOX"));

        assertThrows(RuntimeException.class, () ->
                servico.criarPessoaComFalha("Falha", "Teste", "falha@teste.com", null, null));

        assertEquals(pessoasAntes, dsl.fetchCount(Person.PERSON));
        assertEquals(outboxAntes, dsl.fetchCount(DSL.table("TXNO_OUTBOX")));
    }
}
```

Rode com `./mvnw test -Dtest=OutboxAtomicidadeTest`.

---

## 🖼️ Native Image

### Problema conhecido

```
Caused by: java.lang.ClassNotFoundException: [Ljava.lang.Byte;
    at com.gruelbox.transactionoutbox.DefaultInvocationSerializer$InvocationJsonSerializer.toClass
```

A lib usa `Class.forName` para desserializar o payload JSON. O GraalVM remove a classe `Byte[]` se ela não estiver registrada para reflexão.

### Solução

Crie um arquivo em `src/main/resources/META-INF/native-image/reflection-config.json`:

```json
[
  {
    "name": "[Ljava.lang.Byte;",
    "allDeclaredConstructors": true
  }
]
```

Se colocar em `META-INF/native-image/`, o GraalVM **detecta automaticamente**. Em outros diretórios, é preciso referenciar com `quarkus.native.additional-build-args=-H:ReflectionConfigurationFiles=reflection-config.json`.

> **Atenção**: se aparecerem outros tipos faltando no futuro (ex: `Long[]`, `java.util.Date`), basta adicioná-los ao mesmo JSON.

### Build

```bash
./mvnw clean package -Pnative
./target/*-runner
```

---

## 🗑️ Tabelas criadas pela lib

| Tabela | Quando é criada |
|:---|:---|
| `TXNO_OUTBOX` | Automaticamente no primeiro `flush()` |

### Schema

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

| Coluna | Função |
|:---|:---|
| `processed` | `0` = pendente, `1` = processado |
| `attempts` | Contador de retentativas |
| `nextAttemptTime` | Quando a lib vai tentar de novo (backoff) |
| `blocked` | Motivo do bloqueio após esgotar tentativas |
| `invocation` | JSON com a chamada do método a executar |
| `uniqueRequestId` | Idempotência |

---

## 🔍 Checklist de diagnóstico

| Sintoma | Causa provável | Solução |
|:---|:---|:---|
| `UnsatisfiedResolutionException: QuarkusTransactionManager` | Falta `quarkus.index-dependency` | Adicione as 2 linhas no `application.properties` |
| `ClassNotFoundException: org.acme.XxxPublisherImpl` | Falta `Instantiator` custom | Restaure o `OutboxConfig` com o `Class.forName` customizado |
| `NoSuchMethodException: <init>()` + warning do CDI | Falta `@Unremovable` | Anote o publisher |
| `WARN Closing open connection prior to commit` | Agroal sendo zeloso | Supressão de log (paliativo) ou contribuir com a lib |
| `ClassNotFoundException: [Ljava.lang.Byte;` (native) | Falta registro de reflexão | `reflection-config.json` em `META-INF/native-image/` |
| `MYSQL_8 cannot be resolved` | Typo | É `MY_SQL_8` (com underscores) |

---

## ⚠️ Limitações conhecidas

1. **Não é uma extensão Quarkus nativa** — precisa de `quarkus.index-dependency`.
2. **Instantiator custom é obrigatório** no Quarkus — a lib assume TCCL padrão.
3. **Native image requer registro manual de reflexão** — a lib não é GraalVM-native-ready.
4. **WARN do Agroal é esperado** — sintoma de integração imperfeita, não vazamento real.
5. **Repositório de testes não é transacional por padrão** — se rodar o mesmo `@QuarkusTest` duas vezes, o `UNIQUE KEY email` vai falhar. Limpe com `TRUNCATE` ou use emails únicos.

---

## 📌 Referências

- [transaction-outbox no GitHub](https://github.com/gruelbox/transaction-outbox)
- [README da lib](https://github.com/gruelbox/transaction-outbox/blob/master/README.md)
- [Quarkus — Beans não removíveis](https://quarkus.io/guides/cdi-reference#remove_unused_beans)
- [Quarkus — Indexando dependências](https://quarkus.io/guides/cdi-reference#bean_discovery)
- [GraalVM — Reflection configuration](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
- [MariaDB — SKIP LOCKED](https://mariadb.com/kb/en/select/#skip-locked)

---
