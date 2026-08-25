# T4A Control para Android

Aplicativo nativo para controle e monitoramento do patinete HoneyWhale T4A via Tuya Smart SDK.

## Estado Atual (v0.4.0)

- **Integração Tuya**: Implementação completa do ThingSmart Home SDK (v7.8.0).
- **Gestão de Conta**: Suporte a login via e-mail e gerenciamento de "Home" (Residência).
- **Pareamento e Ativação**: Fluxo de busca, tokenização e ativação de dispositivos BLE Tuya.
- **Painel de Controle**:
    - Velocímetro em tempo real com suporte a **Km/h** e **Mph**.
    - Monitoramento de bateria e tensão.
    - Odômetros Total e Parcial com conversão de unidades.
    - Controle de farol, trava de motor e modos de velocidade (WALK, ECO, RACE, SPORT).
    - Alerta visual de freio acionado.
- **Conectividade**: Reconexão automática via BLE e polling de estado sincronizado.

## Requisitos e Tecnologias

- **Android**: Alvo Android 16 (API 36), suporte mínimo Android 12 (API 31).
- **Stack**: Java nativo, ThingSmart SDK, FastJSON, OkHttp.
- **Permissões**: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION`.

## Configuração do Desenvolvedor

O projeto requer credenciais da Tuya Developer Platform.
1. Copie `tuya.properties.example` para `tuya.properties`.
2. Preencha `TUYA_APP_KEY` e `TUYA_APP_SECRET`.

## Compilação

O projeto utiliza:
- **Gradle**: 9.5.0
- **Android Gradle Plugin (AGP)**: 9.3.2
- **JDK**: 21 (Eclipse Temurin)

A APK de desenvolvimento é gerada em `app/build/outputs/apk/debug/app-debug.apk`.

## Histórico Recente

- **v0.4.0**: Correção de bugs de layout, estabilização da alternância de unidades (km/mi) e correção de erros de sintaxe no formatador de DPs.
- **v0.2.0**: Integração inicial com a nuvem Tuya e renderização dinâmica de controles.

