# T4A Direct BLE — estado, objetivo e roadmap para ESP32

> Documento de decisão e continuidade técnica da linha de BLE direto.
>
> Branch de investigação: `feature/direct-ble-transport-fallback`.
>
> Este documento separa três objetivos que não devem voltar a ser misturados: **runtime BLE independente**, **investigação de comandos/capacidades ocultas** e **provisionamento Tuya**.

## 1. Objetivo

O objetivo técnico do BLE direto deixou de ser provar que é possível conversar com o T4A sem o transporte BLE do SDK Tuya/ThingClips. Isso já foi demonstrado no Android.

O objetivo agora é:

1. consolidar o protocolo necessário para uma implementação independente;
2. portar esse runtime para ESP32;
3. usar a branch experimental Android como laboratório/oráculo para descobrir comandos e capacidades do T4A que o SDK Tuya não expõe;
4. manter provisionamento/obtenção inicial de credenciais separado do runtime BLE.

A linha de navegação da Fase 6 é paralela a este trabalho. Navegação não deve depender da investigação BLE e a investigação BLE não deve carregar alterações de navegação como requisito funcional.

## 2. O que já foi atingido

### 2.1 Transporte GATT

Confirmado e reproduzido diretamente no Android:

- serviço FD50: `0000fd50-0000-1000-8000-00805f9b34fb`;
- característica `0001`: escrita / write without response;
- característica `0002`: notify;
- característica `0003`: read;
- assinatura via CCCD;
- negociação de MTU 247;
- leitura de RSSI;
- reconexão e fallback operacional.

A camada física/GATT não é mais uma incógnita relevante para o port para ESP32.

### 2.2 Protocolo e segurança

Confirmado em runtime:

- protocolo Tuya BLE 4.7;
- security level 2 / NEW;
- dispositivo já vinculado (`bound=true`);
- `v4NeedAuth=false`;
- `v4NeedServerAuth=false`;
- `srand` de 6 bytes.

O caminho clássico baseado em `localKey`/`secKey` como bootstrap legado foi testado e descartado.

### 2.3 Codec independente

`TuyaBle47Codec` é Java puro e não depende de ThingClips. Já implementa:

- envelope do protocolo;
- sequence/acknowledgement/command/payload;
- CRC16;
- AES-128-CBC;
- IV de 16 bytes;
- fragmentação BLE;
- reassembly;
- DeviceInfo;
- Pair;
- Query DPS;
- Publish DPS;
- ACK de DPS report;
- parsing de DeviceInfo.

Essas rotinas são portáveis de forma direta para C/C++/ESP-IDF.

### 2.4 Key schedule necessário ao caminho nativo

Relações comprovadas durante a investigação:

```text
K4     = MD5(loginKey)
K2/K12 = MD5(srand)
```

O caminho nativo atualmente utiliza também:

```text
K5  = MD5(loginKey || srand)
K14 = MD5(loginKeyComplete || secretKey)
K15 = MD5(loginKeyComplete || secretKey || srand)
```

K14 é usado no DeviceInfo e K15 passa a ser derivado após o recebimento de `srand`.

### 2.5 Sessão direta já funcional

O `NativeBleTransport` já executa, sem transporte BLE ThingClips no caminho bem-sucedido:

```text
GATT connect
  -> MTU
  -> discover FD50
  -> subscribe 0002
  -> derive K14
  -> DEVICE_INFO
  -> receive srand
  -> derive K15
  -> PAIR
  -> SESSION_CONNECTED
  -> QUERY_DPS
  -> DPS reports
```

Portanto, a questão principal deixou de ser “é possível?”. O protocolo necessário para um dispositivo já vinculado está suficientemente conhecido para iniciar o port.

### 2.6 DPS/telemetria e comandos

Já existem implementação e entendimento para:

- `DPS_REPORT` (`0x8006`);
- ACK de reports quando solicitado;
- tipos bool, value/int, enum e string;
- Query DPS;
- Publish DPS;
- uso do schema conhecido para interpretação dos DPs.

O Android nativo já consegue receber e interpretar telemetria e publicar DPS pelo caminho independente.

## 3. O que ainda falta

### 3.1 PoC físico no ESP32

Ainda falta provar em hardware que a mesma sessão funciona usando a stack BLE do ESP32/NimBLE.

Esse é o próximo marco técnico e deve ser pequeno: não incluir UI, MQTT, navegação ou automações antes de validar a sessão.

### 3.2 Persistência/entrega das credenciais

O runtime independente ainda pressupõe que os seguintes materiais já existam:

- MAC/endereço do T4A;
- UUID;
- deviceId;
- loginKey/loginKeyComplete (`localKey` no contrato atual);
- secretKey (`securityKey` no contrato atual);
- metadados/schema DPS necessários.

Precisamos definir um formato explícito e estável para exportar esses dados do Android e armazená-los no NVS do ESP32.

### 3.3 Adaptação da camada BLE

Precisamos validar no ESP32:

- MTU efetivamente negociado;
- payload ATT efetivo (`MTU - 3`, conforme a API utilizada);
- pacing de `WRITE_NO_RESPONSE`;
- fila de writes;
- timing entre etapas do handshake;
- reconexão;
- comportamento quando telefone e ESP32 disputam a conexão BLE.

Esses são riscos de transporte/implementação, não lacunas fundamentais do protocolo.

### 3.4 Mapeamento completo de capacidades/DPS

O schema conhecido não deve ser tratado como a lista completa de capacidades do hardware.

A branch experimental continuará sendo usada para investigar:

- comandos não apresentados pelo SDK Tuya;
- DPS não expostos pelo SDK/UI;
- comandos observáveis no protocolo mas sem API pública;
- respostas espontâneas e eventos pouco documentados;
- diferenças entre comandos suportados pelo firmware e comandos expostos pelo produto Tuya.

Toda descoberta deve distinguir claramente:

- **PROVADO**: observado/reproduzido no T4A;
- **MUITO PROVÁVEL**: evidência forte, ainda sem reprodução completa;
- **HIPÓTESE**: candidato a teste;
- **DESCARTADO**: testado e incompatível com o T4A atual.

## 4. Próxima etapa

### Marco ESP32-1 — sessão mínima

Criar um firmware PoC dedicado que:

1. carregue credenciais conhecidas de configuração/NVS;
2. conecte ao MAC conhecido como BLE Central;
3. descubra FD50 e as características necessárias;
4. habilite notify em `0002`;
5. negocie MTU;
6. derive K14;
7. envie DeviceInfo;
8. receba e valide DeviceInfo do T4A;
9. extraia `srand` e derive K15;
10. envie Pair;
11. confirme sessão pronta;
12. envie Query DPS;
13. decodifique e imprima os DPS recebidos pela serial;
14. responda aos DPS reports que exigirem ACK.

**Critério de sucesso:** completar `DEVICE_INFO -> PAIR -> QUERY_DPS` e obter telemetria válida do T4A sem participação do SDK Tuya no runtime BLE.

Nenhum controle do veículo é necessário para considerar esse marco concluído.

### Marco ESP32-2 — robustez

Somente após ESP32-1:

- reconexão automática;
- timeouts e state machine explícita;
- persistência segura das credenciais;
- RSSI;
- tratamento de fragmentos/erros;
- testes prolongados de estabilidade;
- convivência controlada com o Android.

### Marco ESP32-3 — integração

Somente após robustez:

- MQTT de telemetria;
- integração com Home Assistant;
- publicação de comandos previamente validados;
- eventual transferência do papel de conexão principal do telefone para o ESP32.

## 5. Arquitetura pretendida no ESP32

Não portar `NativeBleTransport.java` literalmente. Separar protocolo, sessão e stack BLE:

```text
T4ABle47Codec
  framing
  crypto
  key schedule
  fragmentation/reassembly
  DPS encode/decode

T4ABleSession
  DISCONNECTED
  -> GATT_CONNECTED
  -> SUBSCRIBED
  -> DEVICE_INFO
  -> PAIRED
  -> READY

Esp32BleTransport
  NimBLE / ESP-IDF
  scan/connect/discover
  MTU
  notify
  write without response
  RSSI
```

O codec não deve conhecer NimBLE, MQTT, Home Assistant ou Tuya SDK.

A sessão não deve conhecer Android nem ThingClips.

## 6. Papel futuro da branch `feature/direct-ble-transport-fallback`

Esta branch passa a ter explicitamente dois papéis:

### 6.1 Laboratório de protocolo

Manter probes, introspecção e instrumentação que foram removidos da `master`.

Ela pode usar ThingClips como **oráculo de investigação**, mas não como definição da arquitetura final.

Objetivo principal daqui em diante: descobrir capacidades/comandos adicionais do T4A que o SDK Tuya não mostra ao aplicativo.

Exemplos de investigação válida:

- enumerar/observar comandos e DPS desconhecidos;
- correlacionar mudanças físicas/estado com frames recebidos;
- investigar handlers e tabelas internas do SDK/firmware;
- reproduzir de forma controlada um comando candidato pelo transporte nativo;
- documentar payload, resposta e efeito real.

### 6.2 Bancada de comparação

Quando houver dúvida sobre o port ESP32, usar Android nativo, SDK e probes para comparar:

- bytes de wire;
- sequência de handshake;
- derivação de chaves;
- fragmentação;
- ACKs;
- DPS.

O objetivo não é voltar a depender do SDK, e sim usá-lo como referência enquanto ainda for útil.

## 7. O que não vamos fazer

### Não vamos reintroduzir probes na `master`

A `master` deve permanecer limpa de introspecção e diagnóstico interno ThingClips.

### Não vamos misturar Fase 6 com BLE experimental

Navegação e BLE direto são linhas paralelas. Integrações entre elas só devem ocorrer por contratos estáveis já presentes na `master`.

### Não vamos portar o SDK Tuya para ESP32

O ESP32 implementará apenas o protocolo necessário ao T4A.

### Não vamos tentar provisioning Tuya no ESP32 como próximo passo

Provisionamento inicial e obtenção de credenciais são problemas separados. Primeiro provar e estabilizar o runtime BLE usando credenciais já conhecidas.

### Não vamos resetar/desparear o T4A para facilitar a investigação

O dispositivo atual deve permanecer vinculado. Reset/reprovisionamento só deve ser feito se houver um experimento específico, justificado e autorizado.

### Não vamos fazer brute force criptográfico sem evidência

A estratégia de combinações cegas já apresentou retorno decrescente. Novas investigações devem partir do caminho real de TX/RX, handlers, frames ou efeitos observáveis.

### Não vamos executar comandos desconhecidos de forma indiscriminada

Comandos candidatos devem ser classificados, ter hipótese de efeito e ser testados de maneira controlada. Segurança física do veículo tem prioridade.

### Não vamos adicionar MQTT/UI ao primeiro PoC ESP32

O primeiro objetivo é exclusivamente provar a sessão e a telemetria.

## 8. Critério para considerar a migração BLE para ESP32 tecnicamente resolvida

A migração pode ser considerada tecnicamente resolvida quando o ESP32, usando credenciais previamente provisionadas, conseguir de forma repetível:

1. conectar ao T4A;
2. completar o handshake 4.7 NEW;
3. permanecer conectado;
4. receber e decodificar DPS válidos;
5. responder ACKs necessários;
6. reconectar após perda de sinal/energia;
7. publicar ao menos um DPS previamente conhecido e seguro, quando decidirmos validar TX.

Provisionamento Tuya completamente independente **não é requisito** para esse marco.

## 9. Avaliação atual de risco

Com base no runtime Android já funcional:

- sessão BLE + telemetria no ESP32 com credenciais conhecidas: **alta probabilidade de sucesso (~85–95%)**;
- publicação de DPS/comandos conhecidos: **alta probabilidade (~80–90%)**;
- provisionamento/obtenção inicial de credenciais sem Tuya: **ainda incerto (~30–50%)**.

Esses percentuais são estimativas de engenharia, não resultados de teste no ESP32.

O maior risco deixou de ser a engenharia reversa do framing/handshake e passou a ser a adaptação da stack BLE e a gestão/entrega das credenciais.

## 10. Decisão atual

**Próximo passo de implementação:** ESP32-1, PoC mínimo de sessão e telemetria.

**Próximo passo de investigação Android:** manter `feature/direct-ble-transport-fallback` como laboratório para descobrir comandos/capacidades adicionais não expostos pelo SDK Tuya.

As duas atividades podem evoluir em paralelo e devem compartilhar somente descobertas de protocolo documentadas, não dependências de implementação.
