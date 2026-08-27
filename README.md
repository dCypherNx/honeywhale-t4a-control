# T4A Control para Android

Aplicativo privado para parear, conectar, monitorar e controlar o HoneyWhale T4A pelo Tuya Smart SDK.

## Estado atual (v1.1.0)

Funcional e implementado:

- autenticação da conta privada T4A Control;
- restauração do T4A previamente pareado;
- descoberta, ativação e remoção do pareamento;
- conexão e reconexão BLE automáticas;
- atualização dos DPS recebidos, inclusive mudanças externas;
- comandos absolutos de farol e bloqueio;
- seleção explícita de modo, unidade, piloto automático e tipo de partida;
- painel de velocidade, bateria, odômetro e percurso;
- separação entre apresentação, provisionamento e transporte BLE;
- tratamento defensivo da telemetria de bateria a partir dos padrões observados nos logs reais de rodagem;
- opção para manter a tela ligada enquanto a Activity está visível.

Ainda não implementado ou validado:

- MQTT e discovery para Home Assistant;
- armazenamento de chaves de um protocolo BLE próprio no Android Keystore;
- funcionamento local independente do SDK/nuvem Tuya;
- serviço em primeiro plano para conexão persistente com o aplicativo encerrado ou com a UI indisponível;
- OTA, limite de velocidade e desbloqueio automático por proximidade.

## Arquitetura

- `MainActivity`: cria e atualiza Views, solicita permissões e traduz gestos em intenções. Não importa classes ThingClips/Tuya.
- `backend/T4ABackend`: máquina de estados e regras do T4A. Recebe provisionamento e transporte separados; não cria o adaptador Tuya.
- `backend/T4AProvisioner`: fronteira substituível para conta, inventário, descoberta, ativação e desvinculação.
- `backend/T4ATransport`: fronteira substituível para sessão BLE, conexão, DPS, cache e RSSI.
- `backend/T4AContracts`: modelos e callbacks neutros compartilhados pelas duas fronteiras.
- `backend/TuyaT4APlatform`: adaptador temporário que implementa ambas as fronteiras usando ThingClips.
- `T4AApplication`: ponto de composição que escolhe as implementações concretas entregues ao backend.
- `backend/T4AState`: snapshot imutável entregue à UI; evita que Views acessem objetos mutáveis do SDK.
- `backend/T4ASdk`: bootstrap e encerramento do SDK enquanto o adaptador Tuya continuar presente.

O build executa `verifyTuyaBoundary` antes da compilação e falha se uma classe de domínio voltar a importar ThingClips ou se `T4ABackend` tentar criar diretamente o adaptador Tuya. O GitHub Actions repete a verificação em um checkout limpo em cada PR e push para `master`, garantindo também que os contratos separados estejam no commit. Uma futura implementação BLE própria deverá implementar somente `T4ATransport`; o provisionamento Tuya poderá permanecer separado, sem alterar a UI nem as regras de estado em `T4ABackend`.

## Premissas de comandos e estados

- Todo comando escolhido pelo usuário deve ser enviado ao T4A, mesmo quando o valor solicitado for igual ao valor exibido. A interface não deve suprimir, converter em *toggle* nem considerar redundante uma ação absoluta.
- O envio aceito pelo SDK confirma somente a transmissão do comando. Não confirma que o T4A assumiu o estado solicitado.
- Exceto pelo bloqueio descrito abaixo, a interface só deve alterar um estado depois de receber o respectivo valor do T4A. Não deve presumir nem antecipar o resultado de um comando.
- Farol, unidade milha/km e velocidade podem mudar fisicamente no T4A. O aplicativo deve sempre substituir o valor exibido pelo estado mais recente recebido do dispositivo.
- O bloqueio é comandado somente pelo aplicativo. O aplicativo deve persistir a última ação de bloquear ou desbloquear e usá-la como estado conhecido do bloqueio.
- Ao reconectar, o T4A sempre informa bloqueio como desbloqueado, inclusive quando permanece fisicamente bloqueado. Esse valor inicial de reconexão não deve sobrescrever a última ação de bloqueio lembrada pelo aplicativo.
- Uma resposta posterior que possa ser associada ao comando de bloqueio atual deve ser registrada para diagnóstico, sem transformar o valor incorreto de reconexão em verdade local.

Para os DPS comuns, o backend nunca informa sucesso alterando o estado local: o valor exibido muda apenas quando chega uma atualização recebida do dispositivo ou do cache atualizado pelo SDK. O bloqueio é a exceção deliberada, pois seu estado conhecido deriva da última ação persistida pelo aplicativo.

## Telemetria de bateria: achados dos testes reais

Os logs de duas viagens contínuas mostraram que `battery_percentage` não pode ser tratado como um SOC absoluto sem validação:

- durante a rodagem existem leituras `RX` fisicamente plausíveis abaixo de 100%, com tendência de descarga e forte variação sob carga;
- ocorrem `RX=100` espúrios intercalados entre leituras sub-100, inclusive durante aceleração, portanto esses valores não podem restaurar o SOC depois que a sessão já observou uma leitura válida abaixo de 100%;
- em repouso, as leituras sob carga recuperam vários pontos percentuais, evidenciando *voltage sag* relevante;
- um `INITIAL` de conexão fria pode trazer bateria stale/default, como 100%, mesmo quando a bateria real já está parcialmente descarregada;
- um `INITIAL` de retomada pode ser válido e repetir exatamente a última leitura `RX`, como observado com 74% durante a viagem;
- por isso `INITIAL/cache` não estabelecem bateria por conta própria: somente confirmam o último valor `RX` confiável já conhecido pela mesma instância do backend;
- o raw log permanece intocado para permitir nova calibração do algoritmo com percursos futuros.

Ainda não há estimativa própria de SOC, suavização por janela, curva de descarga calibrada nem cálculo de autonomia restante. Esses itens exigem mais ciclos de uso e pertencem à evolução de telemetria.

## Marco 2: achados e requisitos já identificados

Os testes de rodagem revelaram requisitos arquiteturais que devem ser tratados no Marco 2, junto com MQTT, geolocalização e navegação:

- a sessão BLE não deve depender do ciclo de vida da `MainActivity`;
- bloquear/desbloquear a tela, trocar temporariamente de Activity ou recriar a UI não deve provocar novo `attach`, nova consulta de inventário ou perda da telemetria já ativa;
- o backend/transport deverá viver em componente de duração maior que a UI, preferencialmente um `Foreground Service` ou uma sessão persistente equivalente, com a `MainActivity` apenas assinando o estado existente;
- MQTT e geolocalização devem continuar funcionando mesmo quando a tela estiver bloqueada e a UI não estiver ativa;
- ao retornar para a UI, o aplicativo deverá reassinar o estado corrente sem reinicializar a sessão BLE e sem introduzir `INITIAL` artificialmente por causa da Activity;
- os testes no Galaxy S25 mostraram bloqueio de segurança por detecção de movimento compatível com possível roubo. `FLAG_KEEP_SCREEN_ON` continua útil contra timeout normal, mas não deve ser considerado mecanismo para impedir esse tipo de bloqueio de segurança;
- o aplicativo deve permanecer funcional mesmo que esse bloqueio externo ocorra: a meta do Marco 2 é manter a conexão/telemetria e tornar o evento transparente para o transporte BLE;
- a investigação futura deverá distinguir claramente `INITIAL` gerado por nova sessão real do T4A de `INITIAL` provocado por reanexação do SDK/backend.

A direção desejada para o Marco 2 é, portanto: **UI descartável, sessão BLE persistente e telemetria independente da tela**.

## Compilação

- Android Gradle Plugin: 9.3.2
- Gradle Wrapper: 9.7.1
- Java: 17 no código; JDK 21 para compilação
- compileSdk: 36
- targetSdk: 35
- Tuya Smart SDK: 7.8.0

O arquivo privado `tuya.properties` deve conter `TUYA_APP_KEY` e `TUYA_APP_SECRET`. O APK de desenvolvimento é gerado em `app/build/outputs/apk/debug/app-debug.apk`.

O workflow `.github/workflows/build-apk.yml` prepara um build remoto de debug e publica o APK como artefato do GitHub Actions. Como o projeto atual depende de arquivos locais ignorados pelo Git, a execução remota requer os Secrets configurados para as credenciais Tuya e para os binários privados necessários ao build.
