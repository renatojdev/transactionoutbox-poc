package org.acme;

import io.quarkus.runtime.annotations.RegisterForProxy;

@RegisterForProxy
public interface PessoaPublisher {
    void publicarPessoaCriada(Integer personId, String firstName, String lastName, String email);
}
