# 04 — Native Image

Este documento cobre a compilação da POC para **GraalVM Native Image**, com as configurações que **efetivamente funcionaram**. O caminho até aqui teve várias iterações; este arquivo registra a versão final validada.

---

## 🎯 Resultado validado

| Métrica | Valor |
|:---|:---|
| **Tamanho do binário** | 93 MB |
| **Tempo de build** | ~3m30s (Apple M-series, 8 threads) |
| **Startup macOS (GraalVM 25)** | ~5s |
| **Startup Linux (GraalVM 21/23)** | ~50–200ms (a medir) |
| **RSS esperado em Linux** | ~40–60 MB |
| **End-to-end funcional** | ✅ Validado (HTTP → jOOQ → outbox → worker → publisher) |

> ⚠️ **Sobre o startup de 5s no macOS**: não é culpa da aplicação. Um hello world Quarkus nativo compilado com GraalVM 25 no macOS ARM64 leva o mesmo tempo. A POC adiciona apenas ~10ms. Em Linux, com GraalVM 21 ou 23, o startup cai para ~50–200ms.

---

## 🏗️ Build

```bash
./mvnw clean package -Pnative -DskipTests
./target/transactionoutbox-poc-1.0.0-SNAPSHOT-runner
```

---

## 📋 Problemas encontrados e soluções

A integração com native exigiu resolver uma sequência de `ClassNotFoundException` e `NoSuchMethodException` até chegar à configuração estável. Cada um revelou uma necessidade distinta de registro.

### 1. `ClassNotFoundException: [Ljava.lang.Byte;` (e outros arrays)

**Sintoma**:

```
Caused by: java.lang.ClassNotFoundException: [Ljava.lang.Byte;
    at com.gruelbox.transactionoutbox.DefaultInvocationSerializer$InvocationJsonSerializer.toClass
```

**Causa**: o `DefaultInvocationSerializer` da lib itera sobre uma lista fixa de tipos primitivos, wrappers e arrays para montar o mapa de serialização. O GraalVM remove essas classes por não serem detectadas estaticamente.

**Solução**: registrar todos os arrays no `reflect-config.json`:

```json
[
  { "name": "[Ljava.lang.Byte;",      "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Short;",     "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Integer;",   "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Long;",      "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Float;",     "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Double;",    "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Character;", "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Boolean;",   "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.String;",    "allDeclaredConstructors": true },
  { "name": "[B", "allDeclaredConstructors": true },
  { "name": "[S", "allDeclaredConstructors": true },
  { "name": "[I", "allDeclaredConstructors": true },
  { "name": "[J", "allDeclaredConstructors": true },
  { "name": "[F", "allDeclaredConstructors": true },
  { "name": "[D", "allDeclaredConstructors": true },
  { "name": "[C", "allDeclaredConstructors": true },
  { "name": "[Z", "allDeclaredConstructors": true }
]
```

> **Nota**: a lib também tenta carregar tipos como `BigDecimal[]`, `Date[]`, `UUID[]`, `LocalDate[]` e outros. Ver `reflect-config.json` completo ao final.

---

### 2. `NoSuchMethodException: PersonRecord.<init>()`

**Sintoma**:

```
Caused by: java.lang.NoSuchMethodException: org.acme.jooq.tables.records.PersonRecord.<init>()
    at org.jooq.impl.TableImpl.getRecordConstructor
```

**Causa**: o jOOQ usa reflexão para instanciar o `Record` correspondente à tabela ao fazer `fetchOne()`. As classes geradas pelo codegen não são detectadas automaticamente.

**Solução**: registrar cada tabela e seu record:

```json
{
  "name": "org.acme.jooq.tables.Person",
  "allDeclaredConstructors": true,
  "allDeclaredMethods": true,
  "allDeclaredFields": true
},
{
  "name": "org.acme.jooq.tables.records.PersonRecord",
  "allDeclaredConstructors": true,
  "allDeclaredMethods": true,
  "allDeclaredFields": true
}
```

**Regra**: toda nova tabela gerada pelo jOOQ precisa de **duas entradas** (tabela + record). Para projetos com muitas tabelas, considere automatizar via `GeneratorStrategy` customizado.

---

### 3. `MissingReflectionRegistrationError: proxy class inheriting ['org.acme.PessoaPublisher']`

**Sintoma**:

```
org.graalvm.nativeimage.MissingReflectionRegistrationError:
Cannot reflectively access the proxy class inheriting ['org.acme.PessoaPublisher']
```

**Causa**: o `transaction-outbox` cria um `java.lang.reflect.Proxy` para a interface durante `schedule()`. O GraalVM exige registro explícito de proxies dinâmicos.

**Solução**: `proxy-config.json` em `src/main/resources/META-INF/native-image/`:

```json
[
  { "interfaces": ["org.acme.PessoaPublisher"] }
]
```

> Alternativa: `@RegisterForProxy` na interface. Ambos funcionam. O arquivo foi escolhido por consistência com o `reflect-config.json`.

---

### 4. `NoSuchMethodException: PessoaPublisherImpl_ClientProxy.publicarPessoaCriada(...)`

**Sintoma**:

```
java.lang.NoSuchMethodException: org.acme.PessoaPublisherImpl_ClientProxy
    .publicarPessoaCriada(java.lang.Integer, java.lang.String, java.lang.String, java.lang.String)
    at com.gruelbox.transactionoutbox.Invocation.invoke
```

**Causa**: o `Instantiator` devolvia o **proxy CDI** (`_ClientProxy`) em vez do bean real. O `getDeclaredMethod` da lib procura o método na classe do proxy, que não declara o método (só delega).

**Solução**: usar `ClientProxy.unwrap()` no `Instantiator` para extrair o delegate real. Sem reflexão — funciona em JVM e native.

```java
import io.quarkus.arc.ClientProxy;

@Override
public Object getInstance(String className) {
    try {
        Class<?> clazz = Class.forName(className, true, quarkusClassLoader);
        InstanceHandle<?> handle = Arc.container().instance(clazz);
        if (handle.isAvailable()) {
            return ClientProxy.unwrap(handle.get());
        }
        return clazz.getDeclaredConstructor().newInstance();
    } catch (Exception e) {
        throw new RuntimeException("Erro ao instanciar via Quarkus CDI: " + className, e);
    }
}
```

> **Tentativa que não funcionou**: buscar o método `arc$delegate` via `getMethod()`. Só encontra métodos públicos; em native, pode não estar registrado. `ClientProxy.unwrap()` é a API pública e estável.

---

### 5. `NoSuchMethodException: PessoaPublisherImpl.publicarPessoaCriada(...)`

**Sintoma**: após resolver o proxy, o erro muda para a classe real:

```
java.lang.NoSuchMethodException: org.acme.PessoaPublisherImpl
    .publicarPessoaCriada(java.lang.Integer, java.lang.String, java.lang.String, java.lang.String)
```

**Causa**: a classe `PessoaPublisherImpl` está registrada no `reflect-config.json`, mas com `allDeclaredConstructors: true` apenas. O `allDeclaredMethods` é **obrigatório** para que `getDeclaredMethod(...)` encontre o método.

**Solução**: registrar com `allDeclaredMethods: true`:

```json
{
  "name": "org.acme.PessoaPublisherImpl",
  "allDeclaredMethods": true,
  "allDeclaredConstructors": true
}
```

**Regra**: todo publisher (implementação da interface agendada) precisa dessa entrada.

---

## 📄 `reflect-config.json` completo (validado)

Localização: `src/main/resources/META-INF/native-image/reflect-config.json`

```json
[
  { "name": "[Ljava.lang.Object;",                "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Number;",                "allDeclaredConstructors": true },
  { "name": "[Ljava.math.BigInteger;",            "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Byte;",                  "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Short;",                 "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Integer;",               "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Long;",                  "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Float;",                 "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Double;",                "allDeclaredConstructors": true },
  { "name": "[Ljava.math.BigDecimal;",            "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Character;",             "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.Boolean;",               "allDeclaredConstructors": true },
  { "name": "[Ljava.lang.String;",                "allDeclaredConstructors": true },
  { "name": "[Ljava.util.Date;",                  "allDeclaredConstructors": true },
  { "name": "[Ljava.util.UUID;",                  "allDeclaredConstructors": true },
  { "name": "[Ljava.time.DayOfWeek;",             "allDeclaredConstructors": true },
  { "name": "[Ljava.time.Duration;",              "allDeclaredConstructors": true },
  { "name": "[Ljava.time.Instant;",               "allDeclaredConstructors": true },
  { "name": "[Ljava.time.LocalDate;",             "allDeclaredConstructors": true },
  { "name": "[Ljava.time.LocalDateTime;",         "allDeclaredConstructors": true },
  { "name": "[Ljava.time.LocalTime;",             "allDeclaredConstructors": true },
  { "name": "[Ljava.time.MonthDay;",              "allDeclaredConstructors": true },
  { "name": "[Ljava.time.Period;",                "allDeclaredConstructors": true },
  { "name": "[Ljava.time.OffsetDateTime;",        "allDeclaredConstructors": true },
  { "name": "[Ljava.time.Year;",                  "allDeclaredConstructors": true },
  { "name": "[Ljava.time.YearMonth;",             "allDeclaredConstructors": true },
  { "name": "[Ljava.time.ZoneId;",                "allDeclaredConstructors": true },
  { "name": "[Ljava.time.ZonedDateTime;",         "allDeclaredConstructors": true },
  { "name": "[Ljava.time.ZoneOffset;",            "allDeclaredConstructors": true },
  { "name": "[Ljava.time.Month;",                 "allDeclaredConstructors": true },
  { "name": "[Ljava.time.temporal.ChronoUnit;",   "allDeclaredConstructors": true },
  { "name": "[Lcom.gruelbox.transactionoutbox.Transaction;",  "allDeclaredConstructors": true },
  { "name": "[Lcom.gruelbox.transactionoutbox.TransactionContextPlaceholder;",  "allDeclaredConstructors": true },

  { "name": "org.acme.jooq.tables.Person",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true },

  { "name": "org.acme.jooq.tables.records.PersonRecord",
    "allDeclaredConstructors": true,
    "allDeclaredMethods": true,
    "allDeclaredFields": true },

  { "name": "org.acme.PessoaPublisherImpl",
    "allDeclaredMethods": true,
    "allDeclaredConstructors": true },

  { "name": "[B",  "allDeclaredConstructors": true },
  { "name": "[S",  "allDeclaredConstructors": true },
  { "name": "[I",  "allDeclaredConstructors": true },
  { "name": "[J",  "allDeclaredConstructors": true },
  { "name": "[F",  "allDeclaredConstructors": true },
  { "name": "[D",  "allDeclaredConstructors": true },
  { "name": "[C",  "allDeclaredConstructors": true },
  { "name": "[Z",  "allDeclaredConstructors": true },
  { "name": "[[B", "allDeclaredConstructors": true },
  { "name": "[[I", "allDeclaredConstructors": true }
]
```

---

## 📄 `proxy-config.json`

Localização: `src/main/resources/META-INF/native-image/proxy-config.json`

```json
[
  { "interfaces": ["org.acme.PessoaPublisher"] }
]
```

Toda interface passada para `outbox.schedule(...)` precisa estar aqui.

---

## 🧩 `OutboxConfig.java` (final, validado)

```java
package org.acme;

import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.Instantiator;
import com.gruelbox.transactionoutbox.Persistor;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.quarkus.QuarkusTransactionManager;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ClientProxy;
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
                    if (handle.isAvailable()) {
                        // Extrai o delegate real do proxy CDI — sem reflexão,
                        // funciona em JVM e native. Ver problema #4 acima.
                        return ClientProxy.unwrap(handle.get());
                    }
                    return clazz.getDeclaredConstructor().newInstance();
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

---

## 🧪 Verificação end-to-end

Após subir o binário:

```bash
curl -X POST http://localhost:8081/pessoas \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "Native",
    "lastName": "Test",
    "email": "native-test@example.com",
    "birthDate": "1990-01-15",
    "phoneNumber": "+5511999999999"
  }'
```

**Log esperado** (dentro de ~5s):

```
Processing org.acme.PessoaPublisher.publicarPessoaCriada(<id>, "Native", "Test", "native-test@example.com")
Publicando evento pós-commit: personId=<id>, nome=Native Test, email=native-test@example.com
Processed org.acme.PessoaPublisher.publicarPessoaCriada(<id>, "Native", "Test", "native-test@example.com")
```

**Banco esperado**:

```sql
SELECT * FROM person WHERE email = 'native-test@example.com';  -- 1 linha
SELECT COUNT(*) FROM TXNO_OUTBOX;                               -- 0 (processado e removido)
```

---

## 🧭 Checklist para novos publishers

Ao adicionar um novo publisher (interface + implementação), registre em **3 lugares**:

| Onde | O quê |
|:---|:---|
| `reflect-config.json` | A **implementação** (`XxxPublisherImpl`) com `allDeclaredMethods: true` |
| `proxy-config.json` | A **interface** (`XxxPublisher`) |
| Código | `@Unremovable` na implementação |

Se o publisher usar tipos novos nos parâmetros (ex: `BigDecimal[]`, `LocalDate[]`), eles também vão precisar de entrada no `reflect-config.json`.

---

## ⚠️ Limitações conhecidas

### 1. Startup lento no macOS + GraalVM 25

Um hello world Quarkus nativo leva os mesmos ~5s. **Não é a aplicação**. Em Linux com GraalVM 21 ou 23, o startup cai para ~50–200ms.

### 2. Registro manual por publisher

Cada novo publisher exige entradas manuais nos dois JSONs. Para projetos com muitos publishers, considere:

- **`GeneratorStrategy` customizado no jOOQ Codegen** para gerar as entradas automaticamente.
- **Anotação `@RegisterForReflection`** na própria implementação (não em `targets` de outra classe), que também funciona em alguns casos.
- **`@RegisterForProxy`** na interface (alternativa ao `proxy-config.json`).

### 3. `reflect-config.json` na raiz de `META-INF/native-image/`

O Quarkus gera o próprio `reflect-config.json` e pode sobrescrever o seu se estiver no mesmo local. O caminho atual (`META-INF/native-image/reflect-config.json`) foi validado nesta POC, mas em versões futuras do Quarkus pode ser necessário mover para um subdiretório (ex: `META-INF/native-image/org.acme/app/reflect-config.json`) para evitar colisão.

---

## 📌 Resumo

| Item | Valor |
|:---|:---|
| **Build** | `./mvnw clean package -Pnative -DskipTests` |
| **Tamanho** | ~93 MB |
| **Configs necessárias** | `reflect-config.json` + `proxy-config.json` + `Instantiator` com `ClientProxy.unwrap()` |
| **Problemas resolvidos** | 5 (arrays, jOOQ records, proxy, ClientProxy, método do delegate) |
| **Teste end-to-end** | ✅ Validado |
| **Startup macOS** | ~5s (limitação do GraalVM 25) |
| **Startup Linux** | A medir em produção |

O binário nativo está funcional de ponta a ponta. O único ponto em aberto é medir startup e RSS em Linux, que é o alvo real de produção.

---

**Anterior**: [`03-testes.md`](./03-testes.md)
**Próximo**: [`05-diagnostico.md`](./05-diagnostico.md)
