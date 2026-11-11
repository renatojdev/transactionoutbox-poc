# 03 — Testes

Este documento cobre os testes automatizados que validam o padrão Outbox na POC: atomicidade (rollback limpa tudo) e publicação pós-commit.

---

## 🎯 O que precisa ser provado

O padrão Outbox só tem valor se duas garantias se mantiverem:

1. **Atomicidade**: se a transação falhar, nem o dado de negócio nem o evento ficam persistidos.
2. **Publicação pós-commit**: se a transação commitou, o evento é publicado (eventualmente) pelo worker.

Esses dois testes são o **coração da POC**. Se passarem, o padrão está funcionando. Se falharem, nenhuma outra validação importa.

---

## 🧪 Estrutura dos testes

```
src/test/java/org/acme/
└── OutboxAtomicidadeTest.java
```

### `OutboxAtomicidadeTest.java`

```java
package org.acme;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.acme.jooq.tables.Person;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class OutboxAtomicidadeTest {

    @Inject
    MyService servico;

    @Inject
    DSLContext dsl;

    private int contarPessoas() {
        return dsl.fetchCount(Person.PERSON);
    }

    private int contarOutboxTotal() {
        return dsl.fetchCount(DSL.table("TXNO_OUTBOX"));
    }

    private int contarOutboxPendentes() {
        return dsl.fetchCount(
                DSL.table("TXNO_OUTBOX"),
                DSL.field("processed").eq(false));
    }

    @Test
    void eventoNaoDeveSerPersistidoSeTransacaoFalhar() {
        int pessoasAntes  = contarPessoas();
        int outboxAntes   = contarOutboxTotal();

        assertThrows(RuntimeException.class, () ->
                servico.criarPessoaComFalha(
                        "Falha", "Teste", "falha@teste.com", null, null));

        assertEquals(pessoasAntes, contarPessoas(),
                "person não deve ter novas linhas após rollback");
        assertEquals(outboxAntes, contarOutboxTotal(),
                "TXNO_OUTBOX não deve ter novas linhas após rollback");
    }

    @Test
    void eventoDeveSerPersistidoEPosteriormenteProcessado() throws InterruptedException {
        int pendentesAntes = contarOutboxPendentes();

        servico.criarPessoa("Sucesso", "Teste", "sucesso@teste.com", null, null);

        // Logo após o commit, o evento está persistido e pendente
        int pendentesDepois = contarOutboxPendentes();
        assertEquals(pendentesAntes + 1, pendentesDepois,
                "Deve haver exatamente um novo evento pendente após o commit");

        // Aguarda o worker processar (ele roda a cada 5s)
        Thread.sleep(8000);

        // Após o worker, o evento deve ter sido processado
        assertEquals(pendentesAntes, contarOutboxPendentes(),
                "O evento pendente deve ter sido processado pelo worker");
    }
}
```

---

## 🔬 O que cada teste faz

### Teste 1: `eventoNaoDeveSerPersistidoSeTransacaoFalhar`

**Objetivo**: provar a atomicidade.

**Como funciona**:

1. Conta linhas em `person` e `TXNO_OUTBOX` antes.
2. Chama `criarPessoaComFalha(...)`, que faz o `insert` em `person`, chama `outbox.schedule(...)` e **lança uma exceção** depois.
3. Como o método é `@Transactional` e a exceção é `RuntimeException`, o Narayana marca a transação para rollback.
4. Conta linhas novamente e valida que **nada mudou**.

**O que está sendo provado**: o `outbox.schedule(...)` persiste o evento na **mesma transação** dos dados de negócio. Se a transação falha, ambos são desfeitos atomicamente.

**Método helper necessário em `MyService`**:

```java
@Transactional
public PersonRecord criarPessoaComFalha(String firstName, String lastName,
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

    throw new RuntimeException("Falha simulada após o schedule()");
}
```

> ⚠️ A exceção **precisa ser `RuntimeException`** (unchecked) para o `@Transactional` fazer rollback por padrão. Se você usar uma checked exception, precisa declarar `@Transactional(rollbackOn = Exception.class)`.

### Teste 2: `eventoDeveSerPersistidoEPosteriormenteProcessado`

**Objetivo**: provar que o worker publica o evento após o commit.

**Como funciona**:

1. Conta eventos pendentes (`processed = false`) antes.
2. Chama `criarPessoa(...)`, que faz o `insert` e agenda o evento.
3. Verifica que apareceu **exatamente um** evento pendente.
4. Aguarda 8 segundos (o worker roda a cada 5s).
5. Verifica que o evento pendente foi processado.

**O que está sendo provado**: o worker processa os eventos autonomamente, sem intervenção externa.

---

## ⚠️ Pontos de atenção

### 1. Banco compartilhado entre execuções

Os testes **não são transacionais por padrão**. Se você rodar o teste mais de uma vez, o segundo `criarPessoa("Sucesso", "Teste", "sucesso@teste.com", ...)` vai falhar com:

```
SQLIntegrityConstraintViolationException: Duplicate entry 'sucesso@teste.com' for key 'email'
```

**Soluções possíveis**:

**Opção A (simples)**: limpar o banco antes de cada execução.

```sql
TRUNCATE TABLE person;
TRUNCATE TABLE TXNO_OUTBOX;
```

**Opção B (automatizado)**: usar emails únicos por execução.

```java
import java.util.UUID;

String email = "sucesso-" + UUID.randomUUID() + "@teste.com";
servico.criarPessoa("Sucesso", "Teste", email, null, null);
```

**Opção C (isolado)**: usar `@TestTransaction` na classe para rollback automático após cada teste.

```java
@QuarkusTest
@TestTransaction
class OutboxAtomicidadeTest {
```

> ⚠️ `@TestTransaction` faz rollback do **teste**, mas o worker do outbox roda em **outra thread**, o que pode interferir no teste 2. Use com cuidado.

### 2. Porta em conflito com dev mode

O `@QuarkusTest` sobe uma instância própria do Quarkus. Se o dev mode estiver rodando na mesma porta (8081), o teste falha com `Port already in use`.

**Solução**: pare o dev mode antes de rodar os testes.

```bash
# No terminal do dev mode: pressione 'q' ou Ctrl+C
```

### 3. Latência do worker

O teste 2 aguarda **8 segundos** para o worker processar. Se você reduzir o intervalo do `@Scheduled` para 1s, pode reduzir o sleep para 3s. Mantenha uma margem de segurança de pelo menos 2x o intervalo.

### 4. Fuso horário no `nextAttemptTime`

Se o MariaDB estiver configurado com fuso diferente do JVM, o `nextAttemptTime` calculado pela lib pode não bater com o esperado. Se o worker não processar no tempo previsto, verifique:

```sql
SELECT @@global.time_zone, @@session.time_zone;
```

O ideal é que banco e JVM compartilhem o mesmo fuso, ou ambos usem UTC.

---

## ▶️ Como rodar

### Rodar apenas os testes de atomicidade

```bash
./mvnw test -Dtest=OutboxAtomicidadeTest
```

### Rodar todos os testes

```bash
./mvnw test
```

### Rodar com log detalhado

```bash
./mvnw test -Dtest=OutboxAtomicidadeTest -Dquarkus.log.level=DEBUG
```

---

## ✅ Saída esperada

```
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.523 s
[INFO] BUILD SUCCESS
```

E no log, você deve ver a sequência do worker:

```
Processing org.acme.PessoaPublisher.publicarPessoaCriada(1, "Sucesso", "Teste", "sucesso@teste.com") [<uuid>]
Publicando evento pós-commit: personId=1, nome=Sucesso Teste, email=sucesso@teste.com
Processed org.acme.PessoaPublisher.publicarPessoaCriada(1, "Sucesso", "Teste", "sucesso@teste.com") [<uuid>]
```

Se `Processing` aparecer mas `Processed` não, algo falhou no publisher — verifique se o `PessoaPublisherImpl` tem `@Unremovable` (ver [`02-integracoes-criticas.md`](./02-integracoes-criticas.md)).

---

## 🔍 Verificação manual durante a POC

Enquanto o teste roda, ou após ele, você pode inspecionar o estado do banco:

```sql
-- Contagem geral
SELECT COUNT(*) FROM person;
SELECT COUNT(*) FROM TXNO_OUTBOX;

-- Eventos pendentes (deveria estar vazio após o worker processar)
SELECT id, processed, attempts, blocked, nextAttemptTime
FROM TXNO_OUTBOX
WHERE processed = false;

-- Última invocação persistida
SELECT id, invocation
FROM TXNO_OUTBOX
ORDER BY lastAttemptTime DESC
LIMIT 1;
```

O campo `invocation` contém a serialização JSON do método agendado:

```json
{
  "c":"org.acme.PessoaPublisher",
  "m":"publicarPessoaCriada",
  "p":["Integer","String","String","String"],
  "a":[
    {"t":"Integer","v":1},
    {"t":"String","v":"Sucesso"},
    {"t":"String","v":"Teste"},
    {"t":"String","v":"sucesso@teste.com"}
  ]
}
```

Isso confirma que a lib gravou exatamente o que você esperava.

---

## 🧩 Testes adicionais sugeridos

Se você for levar o padrão para produção, vale adicionar:

### Teste de retry

Forçar uma falha no publisher (ex: `throw new RuntimeException()` no `publicarPessoaCriada`) e verificar que:

- `attempts` incrementa.
- `nextAttemptTime` avança com backoff.
- Após N tentativas, `blocked` é preenchido.

### Teste de idempotência

Disparar o mesmo evento com `uniqueRequestId` idêntico e verificar que a lib não duplica.

### Teste de concorrência

Rodar duas instâncias do worker em paralelo e verificar que cada evento é processado apenas uma vez (prova do `SKIP LOCKED`).

---

## 📊 Checklist de validação

- [ ] `./mvnw clean compile` sem erros.
- [ ] `OutboxAtomicidadeTest.eventoNaoDeveSerPersistidoSeTransacaoFalhar` passa.
- [ ] `OutboxAtomicidadeTest.eventoDeveSerPersistidoEPosteriormenteProcessado` passa.
- [ ] Log mostra `Processing` → `Publicando` → `Processed`.
- [ ] `TXNO_OUTBOX` fica vazia após o worker processar.
- [ ] `person` contém exatamente as linhas dos testes que commitaram.

---

## 📌 Resumo

| Teste | Prova | Duração |
|:---|:---|:---|
| `eventoNaoDeveSerPersistidoSeTransacaoFalhar` | Atomicidade | Rápido (~1s) |
| `eventoDeveSerPersistidoEPosteriormenteProcessado` | Publicação pós-commit | ~8s (aguarda worker) |

Se os dois passarem, a POC está validada e o padrão Outbox está funcionando conforme especificado.

---

**Anterior**: [`02-integracoes-criticas.md`](./02-integracoes-criticas.md)
**Próximo**: [`04-native-image.md`](./04-native-image.md)
