package com.fincatto.documentofiscal.nfe400.webservices;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;

import com.fincatto.documentofiscal.DFLog;
import com.fincatto.documentofiscal.DFModelo;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.NotaFiscalChaveParser;
import com.fincatto.documentofiscal.nfe400.classes.evento.NFEnviaEventoRetorno;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFDetEventoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFDetPagEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFEnviaEventoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFEventoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFInfoEventoEconf;
import com.fincatto.documentofiscal.nfe400.utils.ChaveAcessoUtils;
import com.fincatto.documentofiscal.utils.DFAssinaturaDigital;
import com.fincatto.documentofiscal.utils.DFHttpClient;

/**
 * Classe responsável por enviar o evento de Conciliação Financeira (ECONF), que informa à SEFAZ os pagamentos
 * relacionados a uma NF-e (NT 2024.002 v1.00).
 * O evento é recebido pela SVRS (cOrgao 92) e só é suportado para a NF-e (modelo 55).
 * Classe utiliza de herança de AbstractWSEvento para reaproveitar código comum a todos os eventos.
 */
class WSEconf extends AbstractWSEvento implements DFLog {

    private static final BigDecimal VERSAO_LAYOUT = new BigDecimal("1.00");
    private static final String DESCRICAO_EVENTO = "ECONF";
    private static final String CODIGO_EVENTO = "110750";

    private List<NFDetPagEconf> pagamentos;

    @Override
    protected BigDecimal getVersaoLayout() {
        return VERSAO_LAYOUT;
    }

    @Override
    protected String getCodigoEvento() {
        return CODIGO_EVENTO;
    }

    @Override
    protected String getDescricaoEvento() {
        return DESCRICAO_EVENTO;
    }

    @Override
    protected String getChaveAcesso() {
        return this.chaveAcesso;
    }

    /**
     * @param config Configuração da NF-e utilizada para a comunicação com o web service.
     */
    WSEconf(final NFeConfig config, final DFHttpClient httpClient) {
        super(config, httpClient);
    }

    /**
     * Adiciona os dados necessários para a geração do XML do evento de conciliação financeira.
     *
     * @param chaveAcesso Chave de acesso da NF-e (modelo 55) relacionada ao evento.
     * @param numeroSequencialEvento Número sequencial do evento (1 a 99).
     * @param pagamentos Pagamentos a informar (1 a 100).
     * @return A própria instância de {@link WSEconf} para permitir encadeamento de chamadas.
     * @throws IllegalArgumentException caso a chave não seja de uma NF-e modelo 55.
     */
    WSEconf adicionarDadosEvento(final String chaveAcesso, final int numeroSequencialEvento,
            final List<NFDetPagEconf> pagamentos) {
        if (!DFModelo.NFE.equals(new NotaFiscalChaveParser(chaveAcesso).getModelo())) {
            throw new IllegalArgumentException("O ECONF só é suportado para NF-e (modelo 55)");
        }
        super.chaveAcesso = chaveAcesso;
        this.numeroSequencialEvento = numeroSequencialEvento;
        this.pagamentos = pagamentos;
        return this;
    }

    /**
     * Orquestra o processo de geração, assinatura e transmissão do evento para a SEFAZ.
     *
     * @return {@link NFEnviaEventoRetorno} contendo a resposta do web service.
     * @throws Exception
     */
    public NFEnviaEventoRetorno gerarEnviarEvento() throws Exception {
        final String econfXml = this.gerarDadosXml().toString();
        final String xmlAssinado = new DFAssinaturaDigital(this.config).assinarDocumento(econfXml);
        final String xmlResultado = super.transmiteEvento(xmlAssinado, this.getChaveAcesso());

        return this.config.getPersister().read(NFEnviaEventoRetorno.class, xmlResultado);
    }

    /**
     * Gera os dados XML específicos para o evento de conciliação financeira e chama a criação dos dados comuns.
     *
     * @return {@link NFEnviaEventoEconf} com os dados do evento preenchidos.
     */
    protected NFEnviaEventoEconf gerarDadosXml() {
        final NFDetEventoEconf detEvento = new NFDetEventoEconf();
        detEvento.setDescricaoEvento(WSEconf.DESCRICAO_EVENTO);
        detEvento.setVersao(WSEconf.VERSAO_LAYOUT);
        detEvento.setVersaoAplicativo(WSEconf.VERSAO_LAYOUT.toString());
        detEvento.setPagamentos(this.pagamentos);

        return gerarDadosPaiXml(detEvento);
    }

    /**
     * Gera os dados padrão do XML de evento. Referente os grupos pais do detalhamento do evento (detEvento).
     * O órgão de recepção é sempre a SVRS (92), conforme a NT para modelo 55.
     *
     * @param detEvento Detalhes específicos do evento.
     * @return Objeto NFEnviaEventoEconf com os dados preenchidos.
     */
    private NFEnviaEventoEconf gerarDadosPaiXml(final NFDetEventoEconf detEvento) {
        final NotaFiscalChaveParser chaveParser = new NotaFiscalChaveParser(this.chaveAcesso);
        final NFInfoEventoEconf infoEvento = new NFInfoEventoEconf();
        infoEvento.setAmbiente(this.config.getAmbiente());
        infoEvento.setChave(this.chaveAcesso);
        infoEvento.setCpf(chaveParser.getCpfEmitente());
        infoEvento.setCnpj(chaveParser.getCnpjEmitente());
        infoEvento.setDataHoraEvento(ZonedDateTime.now(this.config.getTimeZone().toZoneId()));
        infoEvento.setId(ChaveAcessoUtils.geraIDevento(this.chaveAcesso, this.getCodigoEvento(), this.numeroSequencialEvento));
        infoEvento.setNumeroSequencialEvento(this.numeroSequencialEvento);
        infoEvento.setOrgao(DFUnidadeFederativa.BRID);
        infoEvento.setCodigoEvento(this.getCodigoEvento());
        infoEvento.setVersaoEvento(this.getVersaoLayout());
        infoEvento.setDetalhesEvento(detEvento);

        final NFEventoEconf evento = new NFEventoEconf();
        evento.setInfoEvento(infoEvento);
        evento.setVersao(this.getVersaoLayout());

        final NFEnviaEventoEconf enviaEvento = new NFEnviaEventoEconf();
        enviaEvento.setEvento(Collections.singletonList(evento));
        enviaEvento.setIdLote(Long.toString(ZonedDateTime.now(this.config.getTimeZone().toZoneId()).toInstant().toEpochMilli()));
        enviaEvento.setVersao(this.getVersaoLayout());
        return enviaEvento;
    }

}
