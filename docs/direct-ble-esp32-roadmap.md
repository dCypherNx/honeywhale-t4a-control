# T4A Direct BLE — roadmap ESP32/IoT

Branch de investigação: `feature/direct-ble-transport-fallback`.

## Objetivo

Portar o runtime BLE já validado no Android para um gateway ESP32 independente, inicialmente usando ESPHome/Home Assistant como bancada. O produto final poderá evoluir para IoT autônomo com MQTT, rede celular e GNSS.

A investigação Android continua em paralelo para descobrir comandos e capacidades que o SDK Tuya não expõe.

## Estado comprovado

No Android já funciona sem transporte BLE ThingClips no caminho direto:

```text
FD50
-> subscribe 0002
-> DEVICE_INFO com K14
-> DeviceInfo/srand
-> K15
-> PAIR
-> QUERY_DPS
-> DPS
```

No ESP32/ESPHome já foi comprovado:

```text
advertisement               OK
conexão GATT                 OK
descoberta FD50              OK
0001 WRITE/NO_RESPONSE       OK
0002 NOTIFY                  OK
0003 READ                    OK
subscribe em 0002            OK
MTU 247                      OK
```

O HA puro é suficiente para observabilidade, mas operações GATT arbitrárias continuam passando pelo ESPHome.

## Próximo teste

Ainda sem portar criptografia para o ESP32.

A branch Android DEBUG gera um `DEVICE_INFO` cifrado e replayável usando `DeviceInfoReplayLoggingTransport` e registra apenas os bytes finais:

```text
ESP32_REPLAY DEVICE_INFO packet=0 len=N hex=...
```

Próximo fluxo:

```text
Android DEBUG
-> gerar/capturar DEVICE_INFO replayável
-> desconectar Android
-> ESPHome conecta no T4A
-> WRITE_NO_RESPONSE em FD50/0001
-> observar NOTIFY em FD50/0002
```

Critério de sucesso: resposta do T4A ao `DEVICE_INFO` reproduzido no ESP32.

## Etapas seguintes

### ESP32-1 — transporte e DeviceInfo

Concluir o replay acima e validar resposta.

### ESP32-2 — sessão completa

Portar apenas o mínimo necessário para:

```text
K14
DEVICE_INFO
parse srand
K15
PAIR
QUERY_DPS
```

### ESP32-3 — telemetria estável

- decode de DPS;
- reconexão;
- watchdog;
- persistência de credenciais;
- RSSI;
- operação prolongada sem telefone.

### ESP32-4 — IoT

- MQTT;
- Home Assistant;
- buffer offline;
- atualização remota;
- Wi-Fi como transporte IP inicial.

### ESP32-5 — independência total

Hardware-alvo deve prever:

- ESP32;
- LTE com SIM físico;
- GNSS;
- alimentação própria a partir do T4A;
- armazenamento persistente;
- operação sem telefone e sem Wi-Fi.

## Arquitetura pretendida

```text
T4A
  <-> BLE 4.7
ESP32
  |- T4ABle47Codec
  |- T4ABleSession
  |- BLE transport
  |- telemetry/cache
  |- MQTT
  |- Wi-Fi/LTE
  `- GNSS
```

ESPHome é bancada e pode permanecer no produto se não limitar BLE, modem, energia ou robustez. Caso limite, a mesma separação de protocolo/sessão deve migrar para ESP-IDF/Arduino sem reescrever o protocolo.

## Credenciais

O runtime independente ainda pressupõe credenciais previamente obtidas pelo Android/Tuya. Provisionamento Tuya no ESP32 não é objetivo imediato.

Precisaremos definir depois um formato seguro para exportar e persistir:

- deviceId;
- UUID;
- MAC;
- loginKey/loginKeyComplete;
- secretKey;
- schema DPS necessário.

## O que não vamos fazer

- reintroduzir probes na `master`;
- misturar navegação/Fase 6 com a investigação BLE;
- portar o SDK Tuya para ESP32;
- reprovisionar/resetar o T4A sem necessidade explícita;
- executar comandos desconhecidos indiscriminadamente;
- fazer brute force criptográfico sem evidência;
- implementar MQTT/celular/GNSS antes de provar a sessão BLE completa.

## Papel permanente da branch experimental

`feature/direct-ble-transport-fallback` é laboratório de protocolo. Deve manter apenas instrumentação que ainda tenha valor para:

- comparar Android e ESP32;
- capturar wire behavior validado;
- descobrir DPS/comandos ocultos;
- confirmar efeito real de comandos candidatos.

A documentação deve registrar estado consolidado, não a cronologia de tentativas descartadas.
