# T4A Control para Android

Aplicativo nativo para controle e monitoramento do patinete HoneyWhale T4A via Tuya Smart SDK.

## Estado Atual (v0.5.1)

- **Integração Tuya**: ThingSmart Home SDK (v7.8.0) com suporte a Android 15+ (SDK 37).
- **Conectividade Agressiva**:
    - Polling de estado a cada **200ms** para velocímetro ultra-responsivo.
    - Ciclo de auto-reconexão forçada a cada 5s (primeiro plano) ou 30s (segundo plano).
    - Persistência de conexão otimizada via ciclo de vida da Activity.
- **Interface Dual (Dash/Settings)**:
    - **Modo Painel**: Focado em pilotagem com velocímetro grande, bateria destacada e alternância rápida de KM/MI.
    - **Modo Configurações**: Acesso a todos os DPs técnicos e log completo de eventos.
- **Painel de Controle**:
    - Velocímetro em tempo real com suporte a **Km/h** e **Mph**.
    - Monitoramento de bateria e tensão.
    - Odômetro Total e Parcial com conversão de unidades.
    - Controle de farol, trava de motor e modos de velocidade (WALK, ECO, RACE, SPORT).
    - Alerta visual de freio acionado.
- **Gestão de Logs**: Visualização simplificada (10 eventos) no painel e histórico completo nas configurações.

## Requisitos e Tecnologias

- **Android**: Alvo Android 15+ (API 37), suporte mínimo Android 12 (API 31).
- **Stack**: Java nativo, ThingSmart SDK, FastJSON, OkHttp 5.5.0.
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

- **v0.5.1**: Upgrade para SDK 37, OkHttp 5.5.0 e implementação de reconexão agressiva (polling de 200ms).
- **v0.4.1**: Restauração da separação entre Painel/Configurações, adição de botões explícitos de alternância KM/Milha e refinamento do log.
- **v0.4.0**: Correção de bugs de layout e estabilização da alternância de unidades.
