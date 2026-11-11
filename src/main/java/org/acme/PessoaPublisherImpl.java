package org.acme;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import io.quarkus.arc.Unremovable;

@Unremovable
@ApplicationScoped
public class PessoaPublisherImpl implements PessoaPublisher {

    private static final Logger LOG = Logger.getLogger(PessoaPublisherImpl.class);

    @Override
    public void publicarPessoaCriada(Integer personId, String firstName, String lastName, String email) {
        // Aqui é executado APÓS o commit da transação.
        // Publique no Event Bus do Vert.x, Kafka, e-mail, etc.
        LOG.infof("Publicando evento pós-commit: personId=%d, nome=%s %s, email=%s",
                  personId, firstName, lastName, email);
    }
}
