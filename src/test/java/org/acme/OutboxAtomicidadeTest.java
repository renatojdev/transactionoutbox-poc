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

        // Após o worker, o evento deve ter sido marcado como processado
        assertEquals(pendentesAntes, contarOutboxPendentes(),
                "O evento pendente deve ter sido processado pelo worker");
    }
}
