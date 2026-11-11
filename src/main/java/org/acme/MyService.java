package org.acme;

import com.gruelbox.transactionoutbox.TransactionOutbox;
import jakarta.transaction.Transactional;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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

        // 1. Insere a pessoa com jOOQ
        PersonRecord person = dsl.insertInto(Person.PERSON)
                .columns(Person.PERSON.FIRST_NAME,
                         Person.PERSON.LAST_NAME,
                         Person.PERSON.EMAIL,
                         Person.PERSON.BIRTH_DATE,
                         Person.PERSON.PHONE_NUMBER)
                .values(firstName, lastName, email, birthDate, phoneNumber)
                .returning()
                .fetchOne();

        // 2. Agenda o evento de domínio para publicação APÓS o commit.
        //    O método 'publicarPessoaCriada' será invocado pelo worker da outbox.
        outbox.schedule(PessoaPublisher.class)
              .publicarPessoaCriada(person.getPersonId(), firstName, lastName, email);
        return person;
    }

    /**
     * Mesmo fluxo do criarPessoa, mas lança uma exceção DEPOIS do schedule().
     * Usado para provar que a transação inteira sofre rollback — inclusive o
     * registro na TXNO_OUTBOX.
     */
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

        outbox.schedule(PessoaPublisher.class)
              .publicarPessoaCriada(person.getPersonId(), firstName, lastName, email);

        // Força o rollback
        throw new RuntimeException("Falha simulada após o schedule() — deve causar rollback de tudo");
    }
}
