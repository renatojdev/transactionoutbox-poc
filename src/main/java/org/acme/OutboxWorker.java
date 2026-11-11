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

    // Executa a cada 5 segundos automaticamente, com o ClassLoader correto e escopo CDI
    @Scheduled(every = "5s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void runWorker() {
        LOG.debug("Processando a outbox...");
        try {
            // Se o flush precisar de uma transação nova:
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
