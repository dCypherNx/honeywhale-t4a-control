# T4A Control para Android

Aplicativo privado para parear, conectar, monitorar e controlar o HoneyWhale T4A pelo Tuya Smart SDK.

## Estado atual (v1.0.0)

Funcional e implementado:

- autenticação da conta privada T4A Control;
- restauração do T4A previamente pareado;
- descoberta, ativação e remoção do pareamento;
- conexão e reconexão BLE automáticas;
- atualização dos DPS recebidos, inclusive mudanças externas;
- comandos absolutos de farol e bloqueio;
- seleção explícita de modo, unidade, piloto automático e tipo de partida;
- painel de velocidade, bateria, odômetro e percurso;
- separação entre apresentação (`MainActivity`) e Tuya/BLE (`backend`).

Ainda não implementado ou validado:

- MQTT e discovery para Home Assistant;
- armazenamento de chaves de um protocolo BLE próprio no Android Keystore;
- funcionamento local independente do SDK/nuvem Tuya;
- serviço em primeiro plano para conexão persistente com o aplicativo encerrado;
- OTA, limite de velocidade e desbloqueio automático por proximidade.

## Arquitetura

- `MainActivity`: cria e atualiza Views, solicita permissões e traduz gestos em intenções. Não importa classes ThingClips/Tuya.
- `backend/T4ABackend`: possui sessão, casa, scan, ativação, conexão, DPS e desvinculação.
- `backend/T4AState`: snapshot imutável entregue à UI; evita que Views acessem objetos mutáveis do SDK.

## Premissas de comandos e estados

- Todo comando escolhido pelo usuário deve ser enviado ao T4A, mesmo quando o valor solicitado for igual ao valor exibido. A interface não deve suprimir, converter em *toggle* nem considerar redundante uma ação absoluta.
- O envio aceito pelo SDK confirma somente a transmissão do comando. Não confirma que o T4A assumiu o estado solicitado.
- Exceto pelo bloqueio descrito abaixo, a interface só deve alterar um estado depois de receber o respectivo valor do T4A. Não deve presumir nem antecipar o resultado de um comando.
- Farol, unidade milha/km e velocidade podem mudar fisicamente no T4A. O aplicativo deve sempre substituir o valor exibido pelo estado mais recente recebido do dispositivo.
- O bloqueio é comandado somente pelo aplicativo. O aplicativo deve persistir a última ação de bloquear ou desbloquear e usá-la como estado conhecido do bloqueio.
- Ao reconectar, o T4A sempre informa bloqueio como desbloqueado, inclusive quando permanece fisicamente bloqueado. Esse valor inicial de reconexão não deve sobrescrever a última ação de bloqueio lembrada pelo aplicativo.
- Uma resposta posterior que possa ser associada ao comando de bloqueio atual deve ser registrada para diagnóstico, sem transformar o valor incorreto de reconexão em verdade local.

Para os DPS comuns, o backend nunca informa sucesso alterando o estado local: o valor exibido muda apenas quando chega uma atualização recebida do dispositivo ou do cache atualizado pelo SDK. O bloqueio é a exceção deliberada, pois seu estado conhecido deriva da última ação persistida pelo aplicativo.

## Compilação

- Android Gradle Plugin: 9.3.2
- Gradle Wrapper: 9.7.1
- Java: 17 no código; JDK 21 para compilação
- compileSdk: 36
- targetSdk: 35
- Tuya Smart SDK: 7.8.0

O arquivo privado `tuya.properties` deve conter `TUYA_APP_KEY` e `TUYA_APP_SECRET`. O APK de desenvolvimento é gerado em `app/build/outputs/apk/debug/app-debug.apk`.
