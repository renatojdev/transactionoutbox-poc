# 02 — Integrações Críticas

Este documento cobre os **4 pontos que a documentação oficial da lib não menciona** e que são obrigatórios para o funcionamento no Quarkus. Cada um foi descoberto na prática durante a POC e, se removido, quebra a aplicação de formas diferentes.

> **Leia antes de rodar.** Os 4 pontos são interdependentes em alguns casos e fáceis de esquecer ao voltar ao projeto depois.

---

## 1. `quarkus.index-dependency` — sem isso, o CDI não vê a lib

### Sintoma

```
jakarta.enterprise.inject.UnsatisfiedResolutionException:
Unsatisfied dependency for type com.gruelbox.transactionoutbox.quarkus.QuarkusTransactionManager
```

A aplicação nem inicia. O erro acontece em build time (ArcProcessor).

### Causa

O Quarkus descobre beans CDI através de um **índice Jandex** (`META-INF/jandex.idx`). Esse índice é gerado automaticamente para o seu código e para dependências que declaram explicitamente que devem ser indexadas.

A `transactionoutbox-quarkus` **não inclui** o índice Jandex. Sem ele, o Quarkus não enxerga a classe `QuarkusTransactionManager` (mesmo estando anotada com `@ApplicationScoped`), e o CDI falha ao tentar injetá-la.

### Solução

Adicione ao `application.properties`:

```properties
quarkus.index-dependency.transactionoutbox.group-id=com.gruelbox
quarkus.index-dependency.transactionoutbox.artifact-id=transactionoutbox-quarkus
```

### Por que não usar `beans.xml`

A alternativa clássica seria colocar um `src/main/resources/META-INF/beans.xml` vazio. Funciona, mas é o mecanismo legado do JEE. A documentação do Quarkus recomenda `quarkus.index-dependency` como o caminho moderno.

---

## 2. `Instantiator` custom — sem isso, `ClassNotFoundException` no worker

### Sintoma

```
WARN [com.gruelbox.transactionoutbox.TransactionOutboxImpl] Temporarily failed to process entry <uuid>
Caused by: java.lang.ClassNotFoundException: org.acme.PessoaPublisherImpl
    at io.quarkus.bootstrap.classloading.QuarkusClassLoader.loadClass
    at com.gruelbox.transactionoutbox.spi.AbstractFullyQualifiedNameInstantiator.getInstance
```

O evento é persistido corretamente na `TXNO_OUTBOX`, mas na hora de processar, a lib não consegue carregar a classe.

### Causa

O `flush()` da lib processa os eventos em **threads internas próprias** (`ExecutorSubmitter`, com nomes como `pool-8-thread-1`). Essas threads:

- **Não são gerenciadas** pelo Quarkus.
- **Não têm** o `QuarkusClassLoader` como Thread Context Class Loader (TCCL).

Quando a lib faz `Class.forName(className)`, a JVM usa o **TCCL da thread atual** para carregar a classe. Sem o `QuarkusClassLoader`, o carregamento falha — mesmo a classe existindo no classpath da aplicação.

### Solução

Substitua o `Instantiator` padrão por um que captura o `QuarkusClassLoader` no momento em que o bean é criado (thread do Quarkus, TCCL correto) e o usa explicitamente no `Class.forName`:

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
            return handle.isAvailable()
                    ? handle.get()
                    : clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Erro ao instanciar via Quarkus CDI: " + className, e);
        }
    }
};
```

E use no builder:

```java
return TransactionOutbox.builder()
        .instantiator(instantiator)   // <-- aqui
        .transactionManager(transactionManager)
        .persistor(Persistor.forDialect(Dialect.MY_SQL_8))
        .build();
```

### Por que não usar `CdiInstantiator.create()`

O `CdiInstantiator` fornecido pela lib **é** um `AbstractFullyQualifiedNameInstantiator`. Ele delega ao `CDI.current().select(clazz).get()` — que por sua vez usa o TCCL da thread para fazer lookup. Em ambientes como `@QuarkusTest` (que roda na main thread), às vezes funciona. Em dev mode com `@Scheduled` ou thread separada, **quebra**.

> **Regra prática**: sempre use o `Instantiator` custom no Quarkus. Não tente "simplificar" removendo ele.

### Assinatura da interface `Instantiator`

A interface `Instantiator` (versão 7.1.769) tem apenas **dois métodos**:

```java
String getName(Class<?> clazz);
Object getInstance(String className);
```

Se você tentar sobrescrever `findClass(String)` ou `getInstance(Class<T>)`, o compilador reclama que não existe supertype. Esses métodos não existem — todo o carregamento de classe fica dentro de `getInstance`.

---

## 3. `@Unremovable` no publisher — sem isso, o bean é removido no build

### Sintoma

```
WARN [io.quarkus.arc.impl] CDI: programmatic lookup problem detected
At least one bean matched the required type and qualifiers but was marked as unused
and removed during build
...
Caused by: java.lang.NoSuchMethodException: org.acme.PessoaPublisher.<init>()
    at java.base/java.lang.Class.getDeclaredConstructor
    at org.acme.OutboxConfig$1.getInstance(OutboxConfig.java:44)
```

O `ClassNotFoundException` do ponto 2 desapareceu, mas agora a lib encontra a classe **e não consegue instanciá-la** — porque ela foi removida do contêiner CDI.

### Causa

O Quarkus faz **dead code elimination em tempo de build**. Beans que não são referenciados por injeção `@Inject` direta (nem por `@Observes`, nem por qualifiers estáticos) são considerados "não usados" e removidos do binário.

Como o `PessoaPublisherImpl` só é referenciado **programaticamente** (via `Arc.container().instance(clazz)` no `Instantiator`), o Quarkus não consegue "ver" essa dependência e o remove.

### Solução

Anote a implementação do publisher:

```java
import io.quarkus.arc.Unremovable;

@Unremovable
@ApplicationScoped
public class PessoaPublisherImpl implements PessoaPublisher {
    // ...
}
```

A anotação `@Unremovable` diz ao Quarkus para manter o bean no contêiner mesmo que ele não seja detectado como usado.

### Alternativa: `UnremovableBeanBuildItem` (para extensões)

Se você estiver escrevendo uma extensão Quarkus (não é o caso), pode registrar beans como não removíveis via `UnremovableBeanBuildItem`. Para aplicações, `@Unremovable` é o caminho.

### Por que a mensagem de erro é confusa

O WARN do ArcProcessor aparece **antes** do `NoSuchMethodException`, mas é fácil de perder no meio do log. O sintoma final (`NoSuchMethodException: <init>()`) sugere que a classe não tem construtor público — o que é falso. A causa real está no WARN acima.

---

## 4. WARN do Agroal — paliativo conhecido

### Sintoma

```
WARN [io.agroal.pool] Datasource '<default>': Closing 1 open connection(s) prior to commit
```

O aviso aparece a cada ciclo do worker (`outbox-worker` ou `executor-thread-1`), poluindo o log.

### Causa

O `QuarkusTransactionManager` da lib faz:

```java
public Connection connection() {
    return dataSource.getConnection();   // obtém do Agroal
}
// ... e nunca fecha explicitamente
```

A intenção é que o **Narayana** (gerenciador JTA) feche a conexão no final da transação. Isso funciona — **não há vazamento**.

Mas o **Agroal** tem um `Synchronization` que roda **antes** do commit e verifica se há conexões "soltas" associadas à thread. Quando encontra uma, emite o WARN e a fecha por conta própria. É um comportamento defensivo do pool.

### Solução paliativa

Suprimir o log:

```properties
quarkus.log.category."io.agroal.pool".level=ERROR
```

Isso eleva o nível para `ERROR`, e os `WARN` deixam de aparecer. Erros reais continuam visíveis.

### Solução definitiva

Contribuir com a lib para que o `QuarkusTransactionManager` registre uma `Synchronization` no JTA que feche a conexão explicitamente no `beforeCompletion`. Isso resolveria a origem do aviso e beneficiaria toda a comunidade.

Enquanto isso não acontece, a supressão é aceitável — é um aviso benigno, não um erro.

> **Não remova a supressão em produção.** Se um dia o Agroal começar a fechar conexões de verdade por causa de um bug real, você vai querer ver esse log. Deixe-o visível em ambientes de debug.

---

## 🧭 Ordem de diagnóstico

Quando algo quebra, siga esta ordem:

| # | Sintoma observado | Ponto a verificar |
|:--|:---|:---|
| 1 | App não inicia, `UnsatisfiedResolutionException` | Ponto 1 (`quarkus.index-dependency`) |
| 2 | Evento persiste mas não processa, `ClassNotFoundException` | Ponto 2 (`Instantiator` custom) |
| 3 | `NoSuchMethodException: <init>()` + WARN do CDI | Ponto 3 (`@Unremovable`) |
| 4 | Log poluído com WARN do Agroal | Ponto 4 (supressão) |

---

## 📊 Tabela resumo

| # | Nome | Sem isso… | Solução | Onde |
|:--|:---|:---|:---|:---|
| 1 | `quarkus.index-dependency` | App não inicia | 2 linhas em `application.properties` | Config |
| 2 | `Instantiator` custom | Worker falha em runtime | Bean `OutboxConfig` custom | Código |
| 3 | `@Unremovable` | Bean some no build | Anotação no publisher | Código |
| 4 | Supressão do Agroal | Log poluído (cosmético) | 1 linha em `application.properties` | Config |

---

## ⚠️ O que **não** fazer

- **Não substitua o `Instantiator` custom** por `CdiInstantiator.create()` — funciona em alguns cenários e falha em outros, o que é pior que falhar sempre.
- **Não remova o `@Unremovable`** mesmo que os testes passem localmente. Em dev mode com `@Scheduled`, o comportamento pode mudar.
- **Não use `beans.xml`** — é legado e o `quarkus.index-dependency` é o caminho moderno.
- **Não deixe a supressão do Agroal em produção** sem documentar. É um paliativo, não uma correção.

---

## 💡 Contribuições para a lib

Os três primeiros pontos poderiam ser resolvidos do lado da lib com:

1. **Índice Jandex no JAR** da `transactionoutbox-quarkus` (resolveria o ponto 1).
2. **`Instantiator` que respeita o TCCL capturado em build time** (resolveria o ponto 2).
3. **Registro automático do bean publisher via `UnremovableBeanBuildItem`** (resolveria o ponto 3).

Se você tiver tempo, abrir issues ou PRs no [repositório](https://github.com/gruelbox/transaction-outbox) é uma boa forma de retribuir. O autor é ativo.

---

**Anterior**: [`01-setup.md`](./01-setup.md)
**Próximo**: [`03-testes.md`](./03-testes.md)
