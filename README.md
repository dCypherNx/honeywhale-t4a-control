# T4A Control para Android

Primeiro marco executável do aplicativo próprio para o HoneyWhale T4A.

## Estado atual

- APK nativa Java, sem SDK ou biblioteca proprietária Tuya.
- Android 12 ou superior; alvo Android 16/API 36.
- Solicita somente `BLUETOOTH_SCAN` e `BLUETOOTH_CONNECT`.
- Descobre anúncios do serviço Tuya BLE `0xFD50`.
- Conecta por GATT, solicita MTU 247 e valida as características do T4A.
- Habilita notificações por meio do CCCD.
- Não chama `writeCharacteristic`; o canal de comando permanece bloqueado.
- Não altera, redefine ou desvincula o pareamento HoneyWhale existente.

## Teste no Galaxy S25+

Em 2026-08-22 a versão `0.1.0-diag` foi instalada no SM-S936B com Android 16.
O teste físico confirmou:

- anúncio FD50 encontrado;
- conexão GATT com status 0;
- MTU 247 negociado com status 0;
- característica de escrita presente;
- característica de notificação presente;
- CCCD habilitado com sucesso;
- nenhuma escrita de comando realizada.

## Compilação portátil

O projeto usa as ferramentas preservadas em `../tools/portable/`:

- Eclipse Temurin JDK 21;
- Android SDK API 36 e Build Tools 36.0.0;
- Gradle 9.1.0;
- Android Gradle Plugin 9.0.1.

A APK de desenvolvimento é gerada em `app/build/outputs/apk/debug/app-debug.apk`.

## Próximo marco

Criar um aplicativo SmartLife App SDK próprio na Tuya Developer Platform e
integrar login, residência e obtenção do token de ativação. A configuração local
está descrita em `../TUYA_CLOUD_PLAN.md` e `tuya.properties.example`.

Depois disso, implementar montagem e desmontagem dos fragmentos Tuya BLE, ainda
sem liberar datapoints. A escrita será adicionada somente após testes unitários
dos contadores, integridade e derivação de sessão.
