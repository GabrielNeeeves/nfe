package com.fincatto.documentofiscal.nfe400.webservices;

import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.FabricaDeObjetosFake;
import com.fincatto.documentofiscal.nfe400.NFeConfigFake;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFDetPagEconf;
import com.fincatto.documentofiscal.utils.DFHttpClient;
import com.fincatto.documentofiscal.utils.DFSoapEnvelope;
import com.fincatto.documentofiscal.utils.DFSoapFaultException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class WSFacadeTest {

    private static final String CHAVE_NOTA_A = "35261072834992000163550010000001231000000010";
    private static final String CHAVE_NOTA_B = "35261072834992000163550010000001241000000010";
    private static final String PROTOCOLO_ECONF = "135260000000001";

    private ConfigComPausa config;
    private HttpCapturador http;
    private WSFacade facade;
    private ExecutorService executor;

    @BeforeEach
    public void criarFacade() throws Exception {
        this.config = new ConfigComPausa();
        this.http = new HttpCapturador(this.config);
        this.facade = criarFacadeComHttp(this.config, this.http);
        this.executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    public void encerrar() throws Exception {
        this.executor.shutdownNow();
        this.facade.close();
    }

    @Test
    public void devePreservarSequenciaDeCadaNotaAoEnviarEconfEmParalelo() throws Exception {
        final List<NFDetPagEconf> pagamentos = Collections.singletonList(FabricaDeObjetosFake.getNFDetPagEconf());

        this.enviarEmParalelo(
                () -> this.facade.enviaEconf(CHAVE_NOTA_A, 1, pagamentos),
                () -> this.facade.enviaEconf(CHAVE_NOTA_B, 2, pagamentos));

        this.verificarSequenciaEnviada(CHAVE_NOTA_A, 1);
        this.verificarSequenciaEnviada(CHAVE_NOTA_B, 2);
    }

    @Test
    public void devePreservarSequenciaDeCadaNotaAoEnviarCancelamentoEconfEmParalelo() throws Exception {
        this.enviarEmParalelo(
                () -> this.facade.enviaCancelamentoEconf(CHAVE_NOTA_A, 1, PROTOCOLO_ECONF),
                () -> this.facade.enviaCancelamentoEconf(CHAVE_NOTA_B, 2, PROTOCOLO_ECONF));

        this.verificarSequenciaEnviada(CHAVE_NOTA_A, 1);
        this.verificarSequenciaEnviada(CHAVE_NOTA_B, 2);
    }

    /**
     * Começa o envio A e o deixa parado no meio da montagem do evento. Com ele parado, faz o envio B inteiro
     * e só então deixa o A terminar. Se a facade reaproveitar o mesmo objeto nos dois envios, o B sobrescreve os dados do A.
     */
    private void enviarEmParalelo(final Callable<?> envioA, final Callable<?> envioB) throws Exception {
        final Future<?> resultadoA = this.executor.submit(envioA);
        this.config.aguardarEnvioParar();

        this.executor.submit(envioB).get(10, TimeUnit.SECONDS);

        this.config.liberarEnvio();
        resultadoA.get(10, TimeUnit.SECONDS);
    }

    private void verificarSequenciaEnviada(final String chave, final int sequencia) {
        final String xml = this.http.xmlEnviadoDaNota(chave);
        Assertions.assertTrue(xml.contains("<nSeqEvento>" + sequencia + "</nSeqEvento>"),
                "A nota " + chave + " deveria ter sido enviada com a sequência " + sequencia);
    }

    /**
     * A WSFacade não permite injetar o cliente HTTP, então ele é trocado por reflexão para não chamar a SEFAZ.
     */
    private static WSFacade criarFacadeComHttp(final NFeConfig config, final DFHttpClient http) throws Exception {
        final WSFacade facade = new WSFacade(config);
        final Field campoHttpClient = WSFacade.class.getDeclaredField("httpClient");
        campoHttpClient.setAccessible(true);
        ((DFHttpClient) campoHttpClient.get(facade)).close();
        campoHttpClient.set(facade, http);
        return facade;
    }

    /**
     * Para a primeira chamada de getTimeZone, que o WSEconf e o WSCancelamentoEconf fazem depois de ler a chave
     * e antes de ler a sequência. Se esses serviços mudarem a ordem em que leem os dados, o teste deixa de reproduzir o problema.
     */
    private static class ConfigComPausa extends NFeConfigFake {
        private final AtomicBoolean primeiraChamada = new AtomicBoolean(true);
        private final CountDownLatch envioParado = new CountDownLatch(1);
        private final CountDownLatch envioLiberado = new CountDownLatch(1);

        void aguardarEnvioParar() throws InterruptedException {
            Assertions.assertTrue(this.envioParado.await(10, TimeUnit.SECONDS), "O primeiro envio não chegou à montagem do evento");
        }

        void liberarEnvio() {
            this.envioLiberado.countDown();
        }

        @Override
        public TimeZone getTimeZone() {
            if (this.primeiraChamada.compareAndSet(true, false)) {
                this.envioParado.countDown();
                try {
                    Assertions.assertTrue(this.envioLiberado.await(15, TimeUnit.SECONDS), "O primeiro envio não foi liberado");
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return super.getTimeZone();
        }
    }

    /**
     * Não chama a SEFAZ: guarda o XML de cada evento enviado e devolve uma resposta fixa.
     */
    private static class HttpCapturador extends DFHttpClient {
        private final List<String> xmlsEnviados = new CopyOnWriteArrayList<>();

        HttpCapturador(final NFeConfig config) throws Exception {
            super(SSLContext.getDefault(), config);
        }

        String xmlEnviadoDaNota(final String chave) {
            return this.xmlsEnviados.stream()
                    .filter(xml -> xml.contains(chave))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Nenhum evento foi enviado para a nota " + chave));
        }

        @Override
        public String postSoap(final String endpoint, final String action, final String envelope) throws IOException, DFSoapFaultException {
            this.xmlsEnviados.add(DFSoapEnvelope.desempacotar(envelope));
            final String resposta = "<retEnvEvento xmlns=\"http://www.portalfiscal.inf.br/nfe\" versao=\"1.00\">"
                    + "<idLote>1</idLote><tpAmb>2</tpAmb><verAplic>TESTE</verAplic><cOrgao>92</cOrgao>"
                    + "<cStat>108</cStat><xMotivo>Servico paralisado momentaneamente</xMotivo></retEnvEvento>";
            return DFSoapEnvelope.envelopar(AbstractWSEvento.NAMESPACE_WSDL, "nfeResultMsg", resposta);
        }
    }
}
