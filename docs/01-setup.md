# 01 — Setup

Este documento cobre dependências, configuração e estrutura de código para integrar `transaction-outbox` com Quarkus + jOOQ + MariaDB.

---

## 📦 Dependências (`pom.xml`)

### Obrigatórias

```xml
<!-- Datasource MariaDB (traz o driver mariadb-java-client transitivamente) -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-jdbc-mariadb</artifactId>
</dependency>

<!-- JTA: necessário para @Transactional e QuarkusTransactionManager -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-narayana-jta</artifactId>
</dependency>

<!-- Scheduler: necessário para o @Scheduled do worker -->
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-scheduler</artifactId>
</dependency>

<!-- jOOQ core -->
<dependency>
    <groupId>org.jooq</groupId>
    <artifactId>jooq</artifactId>
    <version>3.19.6</version>
</dependency>

<!-- Transaction Outbox: runtime + integração Quarkus -->
<dependency>
    <groupId>com.gruelbox</groupId>
    <artifactId>transactionoutbox-quarkus</artifactId>
    <version>7.1.769</version>
</dependency>
<dependency>
    <groupId>com.gruelbox</groupId>
    <artifactId>transactionoutbox-jooq</artifactId>
    <version>7.1.769</version>
</dependency>
```

### REST (para testes manuais)

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest</artifactId>
</dependency>
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-jackson</artifactId>
</dependency>
```

> ⚠️ **Sem `quarkus-rest-jackson`**, o endpoint retorna `415 Unsupported Media Type` mesmo com `Content-Type: application/json` no request. O motor JAX-RS sozinho não desserializa JSON.

### Codegen do jOOQ

O plugin `jooq-codegen-maven` roda em classpath isolado e **não herda** as dependências da aplicação. O driver precisa ser declarado dentro do próprio plugin:

```xml
<plugin>
    <groupId>org.jooq</groupId>
    <artifactId>jooq-codegen-maven</artifactId>
    <version>3.19.6</version>
    <executions>
        <execution>
            <id>generate-jooq</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>generate</goal>
            </goals>
        </execution>
    </executions>
    <dependencies>
        <dependency>
            <groupId>org.mariadb.jdbc</groupId>
            <artifactId>mariadb-java-client</artifactId>
            <version>3.5.3</version>
        </dependency>
    </dependencies>
    <configuration>
        <jdbc>
            <driver>org.mariadb.jdbc.Driver</driver>
            <url>jdbc:mariadb://localhost:3306/outboxtx</url>
            <user>seu_usuario</user>
            <password>sua_senha</password>
        </jdbc>
        <generator>
            <database>
                <name>org.jooq.meta.mariadb.MariaDBDatabase</name>
                <includes>person</includes>
                <inputSchema>outboxtx</inputSchema>
            </database>
            <target>
                <packageName>org.acme.jooq</packageName>
                <directory>target/generated-sources/jooq</directory>
            </target>
        </generator>
    </configuration>
</plugin>
```

### `build-helper-maven-plugin` (para a IDE reconhecer as fontes geradas)

```xml
<plugin>
    <groupId>org.codehaus.mojo</groupId>
    <artifactId>build-helper-maven-plugin</artifactId>
    <version>3.6.0</version>
    <executions>
        <execution>
            <id>add-jooq-sources</id>
            <phase>generate-sources</phase>
            <goals>
                <goal>add-source</goal>
            </goals>
            <configuration>
                <sources>
                    <source>${project.build.directory}/generated-sources/jooq</source>
                </sources>
            </configuration>
        </execution>
    </executions>
</plugin>
```

---

## ⚙️ `application.properties`

```properties
# HTTP
quarkus.http.port=8081

# Datasource
quarkus.datasource.db-kind=mariadb
quarkus.datasource.jdbc.url=jdbc:mariadb://localhost:3306/outboxtx
quarkus.datasource.username=seu_usuario
quarkus.datasource.password=sua_senha

# ⚠️ OBRIGATÓRIO: indexar a lib para o CDI encontrar QuarkusTransactionManager
quarkus.index-dependency.transactionoutbox.group-id=com.gruelbox
quarkus.index-dependency.transactionoutbox.artifact-id=transactionoutbox-quarkus

# Paliativo: suprime WARN do Agroal
# ("Closing open connection prior to commit" — ver 05-diagnostico.md)
quarkus.log.category."io.agroal.pool".level=ERROR
```

---

## 🌱 Criação da tabela de teste

Para a POC, a tabela `person` foi criada assim:

```sql
CREATE TABLE `person` (
  `person_id` int(11) NOT NULL AUTO_INCREMENT,
  `first_name` varchar(50) NOT NULL,
  `last_name` varchar(50) NOT NULL,
  `birth_date` date DEFAULT NULL,
  `email` varchar(100) DEFAULT NULL,
  `phone_number` varchar(20) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT current_timestamp(),
  `updated_at` timestamp NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  PRIMARY KEY (`person_id`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> A tabela `TXNO_OUTBOX` **não precisa ser criada manualmente**. A lib a cria no primeiro `flush()`.

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
└── JooqProducer.java           # Produz o DSLContext manualmente
```

---

## 🔧 Beans

### `OutboxConfig.java`

Produz o `TransactionOutbox`. Este é o bean mais crítico da integração — ver [`02-integracoes-criticas.md`](./02-integracoes-criticas.md) para o racional de cada linha.

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

> ⚠️ **`Dialect.MY_SQL_8`** (com underscores). A enum `Dialect` da lib é separada da `SQLDialect` do jOOQ.

### `JooqProducer.java`

Produz o `DSLContext` usando o `DataSource` gerenciado pelo Quarkus.

```java
package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import javax.sql.DataSource;

@ApplicationScoped
public class JooqProducer {

    @Inject
    DataSource dataSource;

    @Produces
    @ApplicationScoped
    public DSLContext dslContext() {
        return DSL.using(dataSource, SQLDialect.MARIADB);
    }
}
```

### `OutboxWorker.java`

Scheduler que aciona o `flush()` da lib a cada 5 segundos.

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

> **`concurrentExecution = SKIP`** evita sobreposição de execuções caso o `flush()` ultrapasse os 5 segundos.

### `MyService.java`

Serviço de negócio que agenda o evento dentro da transação.

```java
package org.acme;

import com.gruelbox.transactionoutbox.TransactionOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.acme.jooq.tables.Person;
import org.acme.jooq.tables.records.PersonRecord;
import org.jooq.DSLContext;

import java.time.LocalDate;

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

### `PessoaPublisher.java` e `PessoaPublisherImpl.java`

```java
package org.acme;

public interface PessoaPublisher {
    void publicarPessoaCriada(Integer personId, String firstName, String lastName, String email);
}
```

```java
package org.acme;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

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

> **`@Unremovable` é obrigatório**. Sem ele, o Quarkus remove o bean no build e a lib falha ao instanciar. Ver [`02-integracoes-criticas.md`](./02-integracoes-criticas.md).

### `PessoaResource.java`

```java
package org.acme;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.LocalDate;

@Path("/pessoas")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class PessoaResource {

    @Inject
    MyService servico;

    public record CriarPessoaRequest(
            String firstName,
            String lastName,
            String email,
            LocalDate birthDate,
            String phoneNumber) {}

    @POST
    public Response criar(CriarPessoaRequest req) {
        var person = servico.criarPessoa(
                req.firstName(),
                req.lastName(),
                req.email(),
                req.birthDate(),
                req.phoneNumber());
        return Response.status(Response.Status.CREATED)
                .entity(person.intoMap())
                .build();
    }
}
```

---

## 🚀 Como rodar

```bash
# 1. Gere as classes do jOOQ (se não tiver feito ainda)
./mvnw generate-sources

# 2. Rode em dev mode
./mvnw quarkus:dev

# 3. Teste com curl
curl -X POST http://localhost:8081/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Renato",
    "lastName": "Pejr",
    "email": "renato@example.com",
    "birthDate": "1990-01-15",
    "phoneNumber": "+5511999999999"
  }'
```

**Esperado**:
- HTTP `201 Created` com o JSON da pessoa.
- Log mostrando `Publicando evento pós-commit: ...` em até 5 segundos.
- Uma linha em `person` e uma linha (temporária) em `TXNO_OUTBOX`, que depois é removida ou marcada como processada.

---

## ✅ Checklist pós-setup

- [ ] `./mvnw clean compile` sem erros.
- [ ] Classes `org.acme.jooq.tables.Person` e `PersonRecord` geradas.
- [ ] Quarkus inicia sem `UnsatisfiedResolutionException`.
- [ ] `curl` retorna `201 Created`.
- [ ] Log do worker processa o evento em até 5s.
- [ ] `SELECT COUNT(*) FROM person;` mostra a linha inserida.

Se algum item falhar, consulte [`05-diagnostico.md`](./05-diagnostico.md).

---

**Anterior**: [`00-visao-geral.md`](./00-visao-geral.md)
**Próximo**: [`02-integracoes-criticas.md`](./02-integracoes-criticas.md)
