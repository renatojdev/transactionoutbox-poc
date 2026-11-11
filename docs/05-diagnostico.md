# 05 — Diagnóstico

Este documento é um guia de referência rápida para diagnosticar problemas na integração `transaction-outbox` + Quarkus + jOOQ + MariaDB. Cada sintoma aponta para a causa provável e a solução.

---

## 🧭 Fluxo de diagnóstico

Ao encontrar um problema, siga esta ordem:

```
1. A aplicação inicia?
   ├─ NÃO → Seção "Falhas de inicialização"
   └─ SIM
      ↓
2. O endpoint responde?
   ├─ NÃO → Seção "Falhas HTTP"
   └─ SIM
      ↓
3. O dado foi persistido?
   ├─ NÃO → Seção "Falhas de persistência"
   └─ SIM
      ↓
4. O evento foi processado?
   ├─ NÃO → Seção "Falhas de processamento"
   └─ SIM → Tudo OK
```

---

## 🚨 Falhas de inicialização

### `UnsatisfiedResolutionException: QuarkusTransactionManager`

**Sintoma completo**:

```
jakarta.enterprise.inject.UnsatisfiedResolutionException:
Unsatisfied dependency for type com.gruelbox.transactionoutbox.quarkus.QuarkusTransactionManager
- injection target: parameter 'transactionManager' of org.acme.OutboxConfig#transactionOutbox()
```

**Causa**: a lib `transactionoutbox-quarkus` não tem índice Jandex. O Quarkus não descobre o bean `QuarkusTransactionManager`.

**Solução**: adicione ao `application.properties`:

```properties
quarkus.index-dependency.transactionoutbox.group-id=com.gruelbox
quarkus.index-dependency.transactionoutbox.artifact-id=transactionoutbox-quarkus
```

**Verificação**: reinicie o dev mode. O log de startup deve mostrar a feature `arc` (que já estava) e a aplicação deve iniciar normalmente.

---

### `SRCFG00014: The config property quarkus.jooq.dialect is required`

**Sintoma**:

```
java.util.NoSuchElementException: SRCFG00014: The config property quarkus.jooq.dialect
is required but it could not be found in any config source
```

**Causa**: você ainda tem a extensão `quarkus-jooq` no `pom.xml`, mas não definiu o dialeto.

**Solução A (se usar a extensão)**: adicione ao `application.properties`:

```properties
quarkus.jooq.dialect=MARIADB
```

**Solução B (recomendada)**: remova a extensão `quarkus-jooq` e produza o `DSLContext` manualmente. Ver [`01-setup.md`](./01-setup.md), seção `JooqProducer`.

---

### `NoSuchMethodError: ReflectiveClassBuildItem.<init>(boolean, boolean, Class[])`

**Sintoma**:

```
Build step io.quarkiverse.jooq.deployment.JooqProcessor#build threw an exception:
java.lang.NoSuchMethodError: 'void io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem
.<init>(boolean, boolean, java.lang.Class[])'
```

**Causa**: incompatibilidade entre a extensão `quarkus-jooq` (compilada para Quarkus 3.15.x) e o Quarkus 3.40+.

**Solução**: remova a extensão `quarkus-jooq` do `pom.xml` e produza o `DSLContext` manualmente (ver [`01-setup.md`](./01-setup.md), seção `JooqProducer`).

---

## 🌐 Falhas HTTP

### `415 Unsupported Media Type`

**Sintoma**: `curl` retorna `415`, mesmo com `Content-Type: application/json`.

**Causa**: falta a extensão `quarkus-rest-jackson`. O motor JAX-RS sozinho não desserializa JSON.

**Solução**: adicione ao `pom.xml`:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-jackson</artifactId>
</dependency>
```

**Verificação**: no log de startup, a lista `Installed features` deve incluir `rest-jackson`.

---

### `404 Not Found`

**Causas possíveis**:

1. URL errada (confira o `@Path` do recurso).
2. Extensão `quarkus-rest` ausente no `pom.xml`.
3. `PessoaResource` sem `@Path` ou sem ser um bean CDI.

**Diagnóstico**:

```bash
# Lista os endpoints registrados (se tiver smallrye-openapi)
curl http://localhost:8081/q/openapi

# Ou via Dev UI
open http://localhost:8081/q/dev
```

---

### `500 Internal Server Error`

**Causa**: exceção no `MyService`. O log do Quarkus vai mostrar a stack trace completa.

**Erros comuns**:

- `SQLIntegrityConstraintViolationException: Duplicate entry 'email'` → já existe registro com esse email.
- `UnsatisfiedResolutionException` → ver seção de inicialização.
- `NullPointerException` → `outbox` ou `dsl` não foi injetado.

---

## 💾 Falhas de persistência

### Nada aparece em `person` nem em `TXNO_OUTBOX`

**Sintomas**: `curl` retorna `201 Created`, mas as tabelas estão vazias.

**Causas possíveis**:

| Causa | Verificação |
|:---|:---|
| Você está olhando o banco errado | `SELECT DATABASE();` e compare com `quarkus.datasource.jdbc.url` |
| Transação não commitou | Verifique se `@Transactional` está no método público, chamado via CDI (não `new`) |
| Perfil dev aponta para H2 | Verifique se `quarkus-jdbc-h2` está no classpath e a URL aponta para H2 |
| Classe do serviço é `@Singleton` | `@Singleton` não suporta interceptors de `@Transactional` — use `@ApplicationScoped` |

**Diagnóstico**: adicione um log no início de `criarPessoa`:

```java
System.out.println(">>> criarPessoa chamado");
```

Se o log aparece mas o banco fica vazio, o problema é na transação. Se o log não aparece, o problema é HTTP/injeção.

---

### Linha aparece em `person` mas não em `TXNO_OUTBOX`

**Causa**: o `outbox.schedule(...)` não foi executado, ou está fora do escopo transacional.

**Verificação**: confirme que o `schedule` está **dentro** do método `@Transactional`, não em uma chamada separada.

```java
@Transactional
public PersonRecord criarPessoa(...) {
    // ...insert...
    outbox.schedule(...);   // <-- aqui dentro, mesmo método
    return person;
}
```

Se o `schedule` estiver em outro método chamado via `this.outroMetodo()`, o interceptor CDI não é aplicado e a transação pode ser diferente. Verifique também que `outbox.schedule(...)` retorna um proxy não usado — o `return person` deve ser o último statement antes do commit.

---

### Linha aparece em `TXNO_OUTBOX` mas não em `person`

**Causa**: muito improvável. Significa que o `insert` em `person` falhou **após** o `schedule`, mas antes do commit.

**Verificação**: olhe o log de erro do `MyService` e do Narayana. Pode ser constraint violada (unique email), mas nesse caso o rollback deveria ter limpado o `TXNO_OUTBOX` também. Se a linha persistiu, algo anormal aconteceu com o gerenciamento transacional.

---

## 🔄 Falhas de processamento

### `ClassNotFoundException: org.acme.PessoaPublisherImpl` (ou interface)

**Sintoma**:

```
Temporarily failed to process entry <uuid>
Caused by: java.lang.ClassNotFoundException: org.acme.PessoaPublisherImpl
    at io.quarkus.bootstrap.classloading.QuarkusClassLoader.loadClass
    at com.gruelbox.transactionoutbox.spi.AbstractFullyQualifiedNameInstantiator.getInstance
```

**Causa**: falta o `Instantiator` custom no `OutboxConfig`. A thread do worker da lib (`pool-X-thread-Y`) não tem o `QuarkusClassLoader` como TCCL.

**Solução**: adicione o `Instantiator` custom ao `OutboxConfig` (ver [`02-integracoes-criticas.md`](./02-integracoes-criticas.md), ponto 2).

**Verificação**: reinicie, limpe a `TXNO_OUTBOX`, faça um `curl` novo. O log deve mostrar `Processing` seguido de `Processed` sem `Temporarily failed`.

---

### `CDI: programmatic lookup problem detected` + `NoSuchMethodException: <init>()`

**Sintoma**:

```
WARN [io.quarkus.arc.impl] CDI: programmatic lookup problem detected
At least one bean matched the required type and qualifiers but was marked as unused
and removed during build
...
Caused by: java.lang.NoSuchMethodException: org.acme.PessoaPublisher.<init>()
    at org.acme.OutboxConfig$1.getInstance(OutboxConfig.java:44)
```

**Causa**: o `PessoaPublisherImpl` foi removido pelo Quarkus em build time porque não é referenciado via injeção direta.

**Solução**: adicione `@Unremovable` no `PessoaPublisherImpl`:

```java
import io.quarkus.arc.Unremovable;

@Unremovable
@ApplicationScoped
public class PessoaPublisherImpl implements PessoaPublisher {
    // ...
}
```

**Verificação**: reinicie e teste de novo. O WARN do CDI deve desaparecer.

---

### `MYSQL_8 cannot be resolved or is not a field`

**Sintoma**: erro de compilação no `OutboxConfig`.

**Causa**: você escreveu `MYSQL_8` (sem underscore). A constante correta tem underscores.

**Solução**: use `Dialect.MY_SQL_8`. Note que essa é a enum `Dialect` da lib, **não** a `SQLDialect` do jOOQ.

```java
import com.gruelbox.transactionoutbox.Dialect;  // <-- esta

.persistor(Persistor.forDialect(Dialect.MY_SQL_8))
```

---

### Evento processado mas `processed` continua `false`

**Causa**: o worker chama `flush()` mas a transação onde ele roda não é a mesma que atualiza o `processed`. Ou o `flush` está em uma transação que não commitou.

**Verificação**: confirme que o `runWorker` está envolvido em uma transação válida:

```java
@Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
void runWorker() {
    QuarkusTransaction.requiringNew().run(() -> {
        while (outbox.flush()) { }
    });
}
```

---

## 🌐 Falhas no native image

### `ClassNotFoundException: [Ljava.lang.Byte;`

**Sintoma**: o binário nativo **compila**, mas falha em runtime:

```
Caused by: java.lang.ClassNotFoundException: [Ljava.lang.Byte;
    at com.gruelbox.transactionoutbox.DefaultInvocationSerializer$InvocationJsonSerializer.toClass
```

**Causa**: o GraalVM removeu a classe `Byte[]` porque não foi registrada para reflexão.

**Solução**: registre via `@RegisterForReflection`:

```java
@RegisterForReflection(targets = {Byte[].class, byte[].class})
public class ReflectionConfig { }
```

Ou via `src/main/resources/META-INF/native-image/reflection-config.json`:

```json
[
  { "name": "[Ljava.lang.Byte;", "allDeclaredConstructors": true }
]
```

Ver [`04-native-image.md`](./04-native-image.md) para detalhes.

---

### Outras `ClassNotFoundException` no native

**Causa**: qualquer classe acessada via `Class.forName` que não foi detectada pelo GraalVM.

**Solução**: adicione à lista de `targets` da `ReflectionConfig`. O erro em runtime indica exatamente qual classe falta.

**Tipos comuns**:

- `[Ljava.util.Date;` (se usar `Date[]` em parâmetros)
- `[Ljava.lang.String;` (raro, geralmente detectado)
- Classes do jOOQ se mapeadas via reflexão

---

## ⚠️ WARNs benignos

### `WARN [io.agroal.pool] Datasource '<default>': Closing 1 open connection(s) prior to commit`

**Causa**: o `QuarkusTransactionManager` da lib obtém a conexão do `DataSource` e delega ao Narayana para fechá-la. O Agroal emite um aviso preventivo antes do commit. **Não é vazamento de conexão.**

**Solução paliativa**: suprimir o log:

```properties
quarkus.log.category."io.agroal.pool".level=ERROR
```

**Solução definitiva**: contribuir com a lib para registrar uma `Synchronization` que feche a conexão antes do commit.

---

### `WARN [org.jooq.Constants] jOOQ tip of the day: ...`

**Causa**: mensagem informativa do jOOQ, exibida no startup.

**Solução** (opcional): silenciar no `application.properties`:

```properties
quarkus.log.category."org.jooq.Constants".level=ERROR
```

---

## 🧾 Tabela de referência rápida

| Sintoma | Causa provável | Solução |
|:---|:---|:---|
| `UnsatisfiedResolutionException: QuarkusTransactionManager` | Falta `quarkus.index-dependency` | Adicione as 2 linhas em `application.properties` |
| `SRCFG00014: quarkus.jooq.dialect required` | Extensão `quarkus-jooq` sem dialeto | Adicione `quarkus.jooq.dialect=MARIADB` ou remova a extensão |
| `NoSuchMethodError: ReflectiveClassBuildItem` | Extensão `quarkus-jooq` incompatível | Remova a extensão; use `JooqProducer` manual |
| `415 Unsupported Media Type` | Falta `quarkus-rest-jackson` | Adicione a dependência |
| `404 Not Found` | URL/rota errada | Verifique `@Path` e `Installed features` |
| `500 Internal Server Error` | Exceção no serviço | Olhe o log do Quarkus |
| `Duplicate entry 'email'` | Registro já existe | `TRUNCATE` ou email único |
| Nada em `person` nem `TXNO_OUTBOX` | Transação não commitou | Verifique `@Transactional` + chamada via CDI |
| `ClassNotFoundException: PessoaPublisherImpl` | Falta `Instantiator` custom | Adicione o `Instantiator` em `OutboxConfig` |
| `NoSuchMethodException: <init>()` + WARN CDI | Falta `@Unremovable` | Adicione a anotação no publisher |
| `MYSQL_8 cannot be resolved` | Typo | É `MY_SQL_8` (com underscores) |
| `ClassNotFoundException: [Ljava.lang.Byte;` (native) | Falta registro de reflexão | `@RegisterForReflection` ou `reflection-config.json` |
| WARN do Agroal | Comportamento defensivo | Supressão (paliativo) ou contribuir com a lib |

---

## 🔍 Comandos úteis

### Verificar portas em uso

```bash
# macOS
lsof -nP -iTCP:8081 -sTCP:LISTEN

# Linux
ss -tlnp | grep 8081

# Windows
netstat -an | findstr 8081
```

### Inspecionar a outbox

```sql
-- Contagem geral
SELECT COUNT(*) FROM TXNO_OUTBOX;

-- Pendentes
SELECT id, processed, attempts, blocked, nextAttemptTime
FROM TXNO_OUTBOX
WHERE processed = false;

-- Últimas invocações
SELECT id, attempts, lastAttemptTime, nextAttemptTime, blocked,
       LEFT(invocation, 200) AS inv
FROM TXNO_OUTBOX
ORDER BY lastAttemptTime DESC
LIMIT 5;

-- Bloqueados (desistiram)
SELECT id, attempts, blocked, invocation
FROM TXNO_OUTBOX
WHERE blocked IS NOT NULL;
```

### Verificar configuração do Quarkus

```bash
# Endpoint de health
curl http://localhost:8081/q/health

# Dev UI
open http://localhost:8081/q/dev

# Configuração resolvida (em dev mode)
curl http://localhost:8081/q/dev-ui/configuration
```

### Limpar tabelas para reiniciar POC

```sql
TRUNCATE TABLE person;
TRUNCATE TABLE TXNO_OUTBOX;
```

### Verificar versão do MariaDB

```sql
SELECT VERSION();
-- Esperado: 10.6+ (para SKIP LOCKED)

-- Confirmar que SKIP LOCKED funciona
SELECT * FROM TXNO_OUTBOX WHERE processed = false LIMIT 1 FOR UPDATE SKIP LOCKED;
```

---

## ⚠️ Erros que **não** são problemas

| Mensagem | É problema? |
|:---|:---|
| `WARN [io.agroal.pool] Closing open connection` | ❌ Não (benigno) |
| `WARN [org.jooq.Constants] tip of the day` | ❌ Não (informativo) |
| `Processing ... [uuid]` seguido de `Processed ... [uuid]` | ❌ Não (é o comportamento esperado) |
| `ClassNotFoundException` **no build** | ⚠️ Sim (reflexão faltante, mas o build continua) |

---

## 💡 Quando abrir issue na lib

Se você encontrar sintomas que:

1. Não estão documentados neste guia.
2. Persistem mesmo após aplicar as soluções descritas.
3. Sugerem bugs reais na biblioteca (ex: evento duplicado mesmo com `uniqueRequestId`).

Abra uma issue em [gruelbox/transaction-outbox](https://github.com/gruelbox/transaction-outbox/issues) com:

- Versão da lib, Quarkus, MariaDB.
- Stack trace completo.
- Configuração relevante (sem credenciais).
- Passos para reproduzir.

---

**Anterior**: [`04-native-image.md`](./04-native-image.md)
**Próximo**: [`06-operacao.md`](./06-operacao.md)
