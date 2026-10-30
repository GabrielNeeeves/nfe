package com.fincatto.documentofiscal.nfe400.webservices;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Collections;

import com.fincatto.documentofiscal.DFLog;
import com.fincatto.documentofiscal.DFModelo;
import com.fincatto.documentofiscal.DFUnidadeFederativa;
import com.fincatto.documentofiscal.nfe.NFeConfig;
import com.fincatto.documentofiscal.nfe400.NotaFiscalChaveParser;
import com.fincatto.documentofiscal.nfe400.classes.evento.NFEnviaEventoRetorno;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFDetEventoCancelamentoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFEnviaEventoCancelamentoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFEventoCancelamentoEconf;
import com.fincatto.documentofiscal.nfe400.classes.evento.conciliacaofinanceira.NFInfoEventoCancelamentoEconf;
import com.fincatto.documentofiscal.nfe400.utils.ChaveAcessoUtils;
import com.fincatto.documentofiscal.utils.DFAssinaturaDigital;
import com.fincatto.documentofiscal.utils.DFHttpClient;

/**
 * Classe responsável por enviar o cancelamento do evento de Conciliação Financeira (ECONF), que desfaz um ECONF
 * já registrado na SEFAZ (NT 2024.002 v1.00).
 * O evento é recebido pela SVRS (cOrgao 92) e só é suportado para a NF-e (modelo 55).
 * Classe utiliza de herança de AbstractWSEvento para reaproveitar código comum a todos os eventos.
 */
class WSCancelamentoEconf extends AbstractWSEvento implements DFLog {

    private static final BigDecimal VERSAO_LAYOUT = new BigDecimal("1.00");
    private static final String DESCRICAO_EVENTO = "Cancelamento Conciliação Financeira";
    private static final String CODIGO_EVENTO = "110751";

    private String protocoloEconf;

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
     * Construtor da classe WSCancelamentoEconf.
     *
     * @param config Configuração da NF-e utilizada para a comunicação com o web service.
     */
    WSCancelamentoEconf(final NFeConfig config, final DFHttpClient httpClient) {
        super(config, httpClient);
    }

    /**
     * Adiciona os dados necessários para a geração do XML do cancelamento da conciliação financeira.
     *
     * @param chaveAcesso Chave de acesso da NF-e (modelo 55) relacionada ao evento.
     * @param numeroSequencialEvento Número sequencial do evento (1 a 99).
     * @param protocoloEconf Número do protocolo de autorização do ECONF que será cancelado (15 dígitos).
     * @return A própria instância de {@link WSCancelamentoEconf} para permitir encadeamento de chamadas.
     * @throws IllegalArgumentException caso a chave não seja de uma NF-e modelo 55.
     */
    WSCancelamentoEconf adicionarDadosEvento(final String chaveAcesso, final int numeroSequencialEvento, final String protocoloEconf) {
        if (!DFModelo.NFE.equals(new NotaFiscalChaveParser(chaveAcesso).getModelo())) {
            throw new IllegalArgumentException("O cancelamento do ECONF só é suportado para NF-e (modelo 55)");
        }
        super.chaveAcesso = chaveAcesso;
        this.numeroSequencialEvento = numeroSequencialEvento;
        this.protocoloEconf = protocoloEconf;
        return this;
    }

    /**
     * Orquestra o processo de geração, assinatura e transmissão do evento para a SEFAZ.
     *
     * @return {@link NFEnviaEventoRetorno} contendo a resposta do web service.
     * @throws Exception
     */
    public NFEnviaEventoRetorno gerarEnviarEvento() throws Exception {
        final String cancelamentoEconfXml = this.gerarDadosXml().toString();
        final String xmlAssinado = new DFAssinaturaDigital(this.config).assinarDocumento(cancelamentoEconfXml);
        final String xmlResultado = super.transmiteEvento(xmlAssinado, this.getChaveAcesso());

        return this.config.getPersister().read(NFEnviaEventoRetorno.class, xmlResultado);
    }

    /**
     * Gera os dados XML específicos para o cancelamento da conciliação financeira e chama a criação dos dados comuns
     *
     * @return {@link NFEnviaEventoCancelamentoEconf} com os dados do evento preenchidos
     */
    protected NFEnviaEventoCancelamentoEconf gerarDadosXml() {
        final NFDetEventoCancelamentoEconf detEvento = new NFDetEventoCancelamentoEconf();
        detEvento.setDescricaoEvento(WSCancelamentoEconf.DESCRICAO_EVENTO);
        detEvento.setVersao(WSCancelamentoEconf.VERSAO_LAYOUT);
        detEvento.setVersaoAplicativo(WSCancelamentoEconf.VERSAO_LAYOUT.toString());
        detEvento.setNumeroProtocoloEvento(this.protocoloEconf);

        return gerarDadosPaiXml(detEvento);
    }

    /**
     * Gera os dados padrão do XML de evento. Referente os grupos pais do detalhamento do evento (detEvento).
     * O órgão de recepção é sempre a SVRS (92), conforme a NT para o modelo 55.
     *
     * @param detEvento Detalhes específicos do evento.
     * @return Objeto NFEnviaEventoCancelamentoEconf com os dados padrão preenchidos.
     */
    private NFEnviaEventoCancelamentoEconf gerarDadosPaiXml(final NFDetEventoCancelamentoEconf detEvento) {
        final NotaFiscalChaveParser chaveParser = new NotaFiscalChaveParser(this.chaveAcesso);
        final NFInfoEventoCancelamentoEconf infoEvento = new NFInfoEventoCancelamentoEconf();
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

        final NFEventoCancelamentoEconf evento = new NFEventoCancelamentoEconf();
        evento.setInfoEvento(infoEvento);
        evento.setVersao(this.getVersaoLayout());

        final NFEnviaEventoCancelamentoEconf enviaEvento = new NFEnviaEventoCancelamentoEconf();
        enviaEvento.setEvento(Collections.singletonList(evento));
        enviaEvento.setIdLote(Long.toString(ZonedDateTime.now(this.config.getTimeZone().toZoneId()).toInstant().toEpochMilli()));
        enviaEvento.setVersao(this.getVersaoLayout());
        return enviaEvento;
    }
}
