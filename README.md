# T4A Control para Android

Aplicativo privado para parear, conectar, monitorar e controlar o HoneyWhale T4A pelo Tuya Smart SDK.

## Estado atual (v0.6.6)

Funcional e implementado:

- autenticação da conta privada T4A Control;
- restauração do T4A previamente pareado;
- descoberta, ativação e remoção do pareamento;
- conexão e reconexão BLE automáticas;
- atualização dos DPS recebidos, inclusive mudanças externas;
- comandos absolutos de farol e bloqueio;
- seleção explícita de modo, unidade, piloto automático e tipo de partida;
- painel de velocidade, bateria, odômetro, percurso e freio;
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

O backend nunca informa sucesso de um comando alterando o estado local. O valor exibido muda apenas quando o DPS confirmado chega do dispositivo ou do cache atualizado pelo SDK.

## Compilação

- Android Gradle Plugin: 9.3.2
- Gradle Wrapper: 9.7.1
- Java: 17 no código; JDK 21 para compilação
- compileSdk: 36
- targetSdk: 35
- Tuya Smart SDK: 7.8.0

O arquivo privado `tuya.properties` deve conter `TUYA_APP_KEY` e `TUYA_APP_SECRET`. O APK de desenvolvimento é gerado em `app/build/outputs/apk/debug/app-debug.apk`.
