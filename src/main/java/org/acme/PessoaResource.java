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
