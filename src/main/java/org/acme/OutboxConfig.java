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
                        // ClientProxy.unwrap devolve o delegate real se for proxy,
                        // ou o próprio objeto se não for. Sem reflexão — funciona em native.
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
