package com.fincatto.documentofiscal.nfe400.webservices;

import com.fincatto.documentofiscal.nfe400.FabricaDeObjetosFake;
import com.fincatto.documentofiscal.nfe400.NFeConfigFake;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFDetPagEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFEnviaEventoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFInfoEventoEconf;
import com.fincatto.documentofiscal.utils.DFHttpClient;
import com.fincatto.documentofiscal.utils.DFSoapEnvelope;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.AbstractList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public class WSFacadeTest {

    private static final String CHAVE_NOTA_A = "35261072834992000163550010000001231000000010";
    private static final String CHAVE_NOTA_B = "35261072834992000163550010000001241000000010";
    private static final String ALIAS_CERTIFICADO = "econf-teste";
    private static final String SENHA_CERTIFICADO = "senha123";

    @TempDir
    Path diretorioTemporario;

    @Test
    public void devePreservarNotaESequenciaDeCadaPagamentoEmEnviosConcorrentes() throws Exception {
        final ConfiguracaoTeste configuracao = new ConfiguracaoTeste(this.criarCertificadoSintetico());
        final NFDetPagEconf pagamentoA = FabricaDeObjetosFake.getNFDetPagEconf().setValorPagamento(new BigDecimal("100.00"));
        final NFDetPagEconf pagamentoB = FabricaDeObjetosFake.getNFDetPagEconf().setValorPagamento(new BigDecimal("200.00"));
        final CountDownLatch primeiroEnvioEmMontagem = new CountDownLatch(1);
        final CountDownLatch liberarPrimeiroEnvio = new CountDownLatch(1);
        final List<NFDetPagEconf> pagamentosA = pagamentosComPausa(pagamentoA, primeiroEnvioEmMontagem, liberarPrimeiroEnvio);
        final ExecutorService executor = Executors.newFixedThreadPool(2);

        try (HttpCapturador http = new HttpCapturador(configuracao);
             WSFacade facade = criarFacadeComTransporteSimulado(configuracao, http)) {
            final Future<?> primeiroEnvio = executor.submit(() -> facade.enviaEconf(CHAVE_NOTA_A, 1, pagamentosA));
            Future<?> segundoEnvio;
            try {
                Assertions.assertTrue(primeiroEnvioEmMontagem.await(10, TimeUnit.SECONDS), "O primeiro envio nao iniciou a montagem");
                segundoEnvio = executor.submit(() -> facade.enviaEconf(CHAVE_NOTA_B, 2, Collections.singletonList(pagamentoB)));
                try {
                    segundoEnvio.get(5, TimeUnit.SECONDS);
                } catch (final TimeoutException e) {
                    // Uma correcao que serialize os envios tambem e valida. Libera A para que B possa continuar.
                }
            } finally {
                liberarPrimeiroEnvio.countDown();
            }
            primeiroEnvio.get(10, TimeUnit.SECONDS);
            segundoEnvio.get(10, TimeUnit.SECONDS);

            Assertions.assertEquals(2, http.eventosPorValor.size());
            final NFInfoEventoEconf eventoA = http.eventosPorValor.get("100.00");
            final NFInfoEventoEconf eventoB = http.eventosPorValor.get("200.00");
            Assertions.assertNotNull(eventoA, "O pagamento de 100.00 nao foi transmitido");
            Assertions.assertNotNull(eventoB, "O pagamento de 200.00 nao foi transmitido");
            Assertions.assertAll(
                    () -> Assertions.assertEquals(CHAVE_NOTA_A, eventoA.getChave(), "O pagamento de 100.00 pertence a nota A"),
                    () -> Assertions.assertEquals(1, eventoA.getNumeroSequencialEvento(), "A sequencia da nota A deve ser preservada"),
                    () -> Assertions.assertEquals(CHAVE_NOTA_B, eventoB.getChave(), "O pagamento de 200.00 pertence a nota B"),
                    () -> Assertions.assertEquals(2, eventoB.getNumeroSequencialEvento(), "A sequencia da nota B deve ser preservada"));
        } finally {
            liberarPrimeiroEnvio.countDown();
            executor.shutdownNow();
            Assertions.assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS), "As tarefas de envio nao foram encerradas");
        }
    }

    private static List<NFDetPagEconf> pagamentosComPausa(final NFDetPagEconf pagamento,
            final CountDownLatch montagemIniciada, final CountDownLatch continuarMontagem) {
        return new AbstractList<NFDetPagEconf>() {
            private final AtomicBoolean primeiraLeitura = new AtomicBoolean(true);

            @Override
            public NFDetPagEconf get(final int indice) {
                if (indice != 0) {
                    throw new IndexOutOfBoundsException("Indice: " + indice);
                }
                return pagamento;
            }

            @Override
            public int size() {
                if (this.primeiraLeitura.compareAndSet(true, false)) {
                    // Pausa apos o servico capturar os pagamentos de A, antes de ler sua chave e sequencia.
                    montagemIniciada.countDown();
                    try {
                        Assertions.assertTrue(continuarMontagem.await(15, TimeUnit.SECONDS), "A montagem do primeiro envio nao foi liberada");
                    } catch (final InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Montagem interrompida", e);
                    }
                }
                return 1;
            }
        };
    }

    private static WSFacade criarFacadeComTransporteSimulado(final ConfiguracaoTeste configuracao,
            final HttpCapturador http) throws Exception {
        final WSFacade facade = new WSFacade(configuracao);
        try {
            // A API publica nao oferece injecao do transporte. Substitui apenas o HTTP, mantendo o fluxo real do evento.
            final Field campoHttpClient = WSFacade.class.getDeclaredField("httpClient");
            campoHttpClient.setAccessible(true);
            ((DFHttpClient) campoHttpClient.get(facade)).close();
            campoHttpClient.set(facade, http);
            return facade;
        } catch (final Exception e) {
            facade.close();
            throw e;
        }
    }

    private KeyStore criarCertificadoSintetico() throws Exception {
        final Path arquivo = this.diretorioTemporario.resolve("certificado.p12");
        final Path log = this.diretorioTemporario.resolve("keytool.log");
        final String executavel = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
        final Path keytool = Path.of(System.getProperty("java.home"), "bin", executavel);
        final Process processo = new ProcessBuilder(keytool.toString(), "-genkeypair", "-noprompt",
                "-alias", ALIAS_CERTIFICADO, "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
                "-dname", "CN=Teste sintetico ECONF", "-keystore", arquivo.toString(), "-storetype", "PKCS12",
                "-storepass", SENHA_CERTIFICADO, "-keypass", SENHA_CERTIFICADO)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            Assertions.assertTrue(processo.waitFor(30, TimeUnit.SECONDS), "Tempo esgotado ao gerar certificado de teste");
            Assertions.assertEquals(0, processo.exitValue(), () -> "Falha ao gerar certificado de teste. Consulte " + log);
        } finally {
            if (processo.isAlive()) {
                processo.destroyForcibly();
                processo.waitFor(5, TimeUnit.SECONDS);
            }
        }
        final KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream entrada = Files.newInputStream(arquivo)) {
            keyStore.load(entrada, SENHA_CERTIFICADO.toCharArray());
        }
        return keyStore;
    }

    private static class ConfiguracaoTeste extends NFeConfigFake {
        private final KeyStore keyStore;

        ConfiguracaoTeste(final KeyStore keyStore) {
            this.keyStore = keyStore;
        }

        @Override
        public KeyStore getCertificadoKeyStore() {
            return this.keyStore;
        }

        @Override
        public KeyStore getCadeiaCertificadosKeyStore() {
            return this.keyStore;
        }

        @Override
        public String getCertificadoSenha() {
            return SENHA_CERTIFICADO;
        }

        @Override
        public String getCertificadoAlias() {
            return ALIAS_CERTIFICADO;
        }
    }

    private static class HttpCapturador extends DFHttpClient {
        private final ConfiguracaoTeste configuracao;
        private final Map<String, NFInfoEventoEconf> eventosPorValor = new ConcurrentHashMap<>();

        HttpCapturador(final ConfiguracaoTeste configuracao) throws Exception {
            super(SSLContext.getDefault(), configuracao);
            this.configuracao = configuracao;
        }

        @Override
        public String postSoap(final String endpoint, final String action, final String envelope) throws IOException {
            try {
                final String xml = DFSoapEnvelope.desempacotar(envelope);
                final NFEnviaEventoEconf envio = this.configuracao.getPersister().read(NFEnviaEventoEconf.class, xml);
                final NFInfoEventoEconf evento = envio.getEvento().get(0).getInfoEvento();
                final String valor = evento.getDetalhesEvento().getPagamentos().get(0).getValorPagamento();
                this.eventosPorValor.put(valor, evento);
            } catch (final Exception e) {
                throw new IOException("Nao foi possivel capturar o XML do ECONF", e);
            }
            final String resposta = "<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\">"
                    + "<idLote>1</idLote><tpAmb>2</tpAmb><verAplic>TESTE</verAplic><cOrgao>92</cOrgao>"
                    + "<cStat>108</cStat><xMotivo>Servico paralisado momentaneamente</xMotivo></retEnvEvento>";
            return DFSoapEnvelope.envelopar(AbstractWSEvento.NAMESPACE_WSDL, "nfeResultMsg", resposta);
        }
    }
}
