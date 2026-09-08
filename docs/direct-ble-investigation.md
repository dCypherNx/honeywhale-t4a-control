# T4A Direct BLE — estado consolidado

Branch: `feature/direct-ble-transport-fallback`

Este documento substitui a cronologia de probes antigos. Mantém apenas fatos atualmente úteis para continuidade técnica.

## O que está comprovado

### GATT

- T4A visível por BLE no MAC observado.
- Serviço: `0000fd50-0000-1000-8000-00805f9b34fb`.
- `0001`: WRITE + WRITE_NO_RESPONSE.
- `0002`: NOTIFY.
- `0003`: READ.
- CCCD de `0002` aceita inscrição normalmente.
- MTU 247 funciona no Android e foi reproduzido no ESP32/ESPHome.

### Sessão/protocolo

- Protocolo efetivo: Tuya BLE 4.7.
- Security level: NEW.
- Dispositivo já vinculado.
- `v4NeedAuth=false`.
- `v4NeedServerAuth=false`.
- `srand` tem 6 bytes.

### Codec independente

`TuyaBle47Codec` é Java puro e não depende de ThingClips para:

- framing;
- sequence/ack/command/payload;
- CRC16;
- AES-128-CBC;
- IV;
- fragmentação/reassembly;
- DeviceInfo;
- Pair;
- Query DPS;
- Publish DPS;
- ACK de DPS report;
- parsing de DeviceInfo.

### Derivações usadas pelo caminho nativo validado

```text
K14 = MD5(loginKeyComplete || secretKey)
K15 = MD5(loginKeyComplete || secretKey || srand)
```

Outras relações observadas durante a investigação permanecem apenas como referência histórica e não são necessárias para o caminho nativo atual.

### Fluxo direto Android validado

```text
GATT connect
-> MTU 247
-> discover FD50
-> subscribe 0002
-> derive K14
-> DEVICE_INFO
-> receive DeviceInfo/srand
-> derive K15
-> PAIR
-> SESSION_CONNECTED
-> QUERY_DPS
-> DPS reports
```

O transporte BLE ThingClips não participa do caminho direto quando ele completa com sucesso. O SDK permanece como fallback/provisioner.

## PoC ESP32/ESPHome — estado atual

Já reproduzido em ESP32 com ESPHome:

```text
BLE connect                         OK
FD50 discovery                      OK
0001/0002/0003 discovery            OK
subscribe em 0002                   OK
MTU 247                             OK
```

A conexão passiva, sem iniciar o protocolo Tuya, permanece silenciosa e o T4A encerra a sessão após algum tempo. Isso é compatível com a necessidade de iniciar `DEVICE_INFO`.

## Instrumentação atual para replay no ESP32

A branch inclui `DeviceInfoReplayLoggingTransport` em builds DEBUG.

Ao iniciar uma conexão ele:

1. recebe as credenciais já existentes pelo contrato de dispositivo;
2. deriva K14 em memória;
3. gera um `DEVICE_INFO` válido para MTU 247;
4. usa IV fixo apenas para tornar a captura reproduzível;
5. fragmenta exatamente como `TuyaBle47Codec`;
6. registra somente os bytes finais em HEX.

Formato esperado:

```text
ESP32_REPLAY DEVICE_INFO packets=N mtu=247 selector=14 writeType=NO_RESPONSE
ESP32_REPLAY DEVICE_INFO packet=0 len=N hex=...
```

Nenhuma chave, credential ou material derivado é registrado.

O pacote capturado poderá ser enviado pelo ESPHome diretamente para `FD50/0001` usando `WRITE_NO_RESPONSE`.

## O que foi descartado

Não usar como base de implementação:

- bootstrap Tuya clássico com `localKey`;
- envio raw de `secKey`;
- assumir `pv=2.2` como protocolo real;
- brute force de derivações criptográficas;
- polling de objetos internos efêmeros do SDK;
- reproduzir frames antigos de fases anteriores da investigação.

Esses caminhos foram úteis para descoberta, mas não fazem parte da arquitetura atual.

## Papel desta branch

A branch permanece deliberadamente experimental e tem dois objetivos:

1. servir como bancada de referência para o protocolo BLE direto e port para ESP32;
2. investigar comandos/DPS/capacidades do T4A não expostos pelo SDK/UI Tuya.

Probes podem permanecer nesta branch, mas não devem voltar para `master`.

## Próximo passo

1. instalar build DEBUG desta branch;
2. conectar ao T4A uma vez pelo Android;
3. capturar as linhas `ESP32_REPLAY DEVICE_INFO`;
4. desconectar o Android;
5. conectar o ESP32 via ESPHome;
6. enviar o pacote capturado em `0001`;
7. observar a resposta em `0002`.

Critério de sucesso imediato: receber uma resposta do T4A ao `DEVICE_INFO` reproduzido pelo ESP32.
