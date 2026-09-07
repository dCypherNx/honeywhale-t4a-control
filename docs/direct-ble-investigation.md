# RideDash / HoneyWhale T4A — investigação de BLE direto

> Estado consolidado da investigação na branch `feature/direct-ble-transport-fallback`.
>
> Objetivo final: transportar a conexão Bluetooth e o tráfego do T4A para uma implementação independente do SDK Tuya/ThingClips, com possibilidade de execução em ESP32. Durante a investigação, o SDK permanece como fallback operacional e referência de comportamento.

## 1. Regras e premissas da investigação

- Não desparear, resetar, reativar ou reprovisionar o T4A durante os testes.
- Preservar o SDK Tuya/ThingClips como fallback funcional.
- Investigar primeiro por observação passiva e introspecção; evitar comandos experimentais que possam alterar o estado do veículo.
- Não registrar em log valores de chaves/segredos; comparar valores em memória e registrar apenas metadados/equivalências.
- Não promover a branch para `master` antes de validação física.
- O alvo não é apenas substituir chamadas do SDK no Android: é entender transporte, framing, autenticação, sessão e DPS o suficiente para uma futura implementação no ESP32.

## 2. Arquitetura criada para a migração

A aplicação foi previamente preparada para separar provisionamento e transporte BLE. Na branch experimental, a composição evoluiu para permitir tentativa direta antes do fallback Tuya.

Fluxo conceitual usado durante a investigação:

```text
RideDash
  -> transporte/probes BLE diretos
  -> fallback temporizado
  -> introspecção do runtime ThingClips (DEBUG)
  -> Tuya/ThingClips BLE operacional
  -> T4A
```

Essa separação foi bem-sucedida: foi possível fazer dezenas de experimentos sem perder o caminho operacional existente.

## 3. Descobertas do transporte GATT

### 3.1 Serviço e características

O T4A expõe o serviço Tuya BLE:

```text
Service: 0000fd50-0000-1000-8000-00805f9b34fb
```

Características relevantes:

```text
0001 -> WRITE / WRITE_NO_RESPONSE -> telefone para T4A
0002 -> NOTIFY                  -> T4A para telefone
0003 -> READ
```

A característica `0002` aceita assinatura via CCCD normalmente.

### 3.2 MTU

A negociação direta de MTU 247 foi bem-sucedida.

### 3.3 Resultado

A camada física/GATT deixou de ser uma incógnita. Um ESP32 consegue, em princípio, reproduzir conexão, descoberta, MTU, assinatura de notificações e escrita no canal correto.

O bloqueio atual está acima do GATT: protocolo, autenticação, sessão e criptografia.

## 4. Metadados do dispositivo

Foram confirmados durante a investigação:

```text
productId: daccqvyo
uuid:      44c621c46f26a07e
secKey:    16 bytes
ability:   5
singleBle: true
category:  hbc
categoryCode: hbc_2b_1
```

O `DeviceBean` chegou a expor `pv=2.2`, mas essa informação não representa o protocolo efetivamente negociado em runtime.

## 5. Protocolo efetivamente utilizado

A introspecção do runtime ThingClips eliminou a hipótese de que o T4A estivesse operando no protocolo clássico inferido inicialmente.

Foi confirmado:

```text
protocolVersion = 4.7
securityLevel   = 2 / NEW
```

Informações observadas em `DeviceInfoRep`/`DeviceInfoRsp`:

```text
isBind             = true
newAuthKey         = true
security update    = true
v4NeedAuth         = false
v4NeedServerAuth   = false
srandLength        = 6
authKeyLength      = 64
```

Portanto, o dispositivo está pareado e usa o caminho de segurança NEW do protocolo 4.7.

## 6. Materiais de sessão observados

No `ConnectParam` foram observados:

```text
loginKeyLength          = 6
loginKeyCompleteLength  = 16
secretKeyLength         = 16
localKeyLength          = 0
devIdLength             = 16
uuidLength              = 16
```

Um objeto interno de segurança (`qqqqdqq`) revelou duas associações importantes sem necessidade de registrar os valores:

```text
byte[6]  bdpdqbp == DeviceInfoRep.srand
String64 bppdpdq == DeviceInfoRsp.authKey
```

Isso conectou o estado interno ofuscado do SDK às estruturas de protocolo nomeadas.

## 7. Slots de chave do runtime

O controller interno expõe `getSecretKey(slot)`.

### 7.1 Antes da negociação

Slots preenchidos:

```text
4
12
14
```

### 7.2 Depois da negociação

Slots preenchidos:

```text
2
4
5
12
14
15
```

Foi comprovado que:

```text
slot2 == slot12
```

Os getters nomeados foram mapeados:

```text
getSecretKey2  -> slots 2 e 12
getSecretKey12 -> slots 2 e 12
getSecretKey4  -> slot 4
getSecretKey5  -> slot 5
getSecretKey14 -> slot 14
getSecretKey15 -> slot 15
```

`key1`, `key1Random`, `key11` e `key11Random` permaneceram vazios no cenário observado.

## 8. Descoberta criptográfica conclusiva

O probe de derivação passou a calcular candidatos localmente e comparar byte a byte com os slots do runtime, registrando somente o nome da transformação e o slot correspondente.

Foram obtidas duas relações exatas e repetíveis:

```text
K4 = slot4 = MD5(loginKey)
```

E, depois que `srand` passa a existir:

```text
K2 = K12 = slot2 = slot12 = MD5(srand)
```

Essas relações não são correlação por tamanho, timing ou aparência: são igualdade byte a byte entre o resultado calculado e a chave retornada pelo runtime.

### Importância

Isso estabelece duas âncoras reproduzíveis fora do SDK:

1. uma chave derivável antes do `DeviceInfo`: `MD5(loginKey)`;
2. uma chave dinâmica de sessão derivável após receber o `srand`: `MD5(srand)`.

A presença de MD5 lembra mecanismos Tuya anteriores, mas **não prova que o protocolo 4.7 NEW seja equivalente ao protocolo clássico**. A negociação e o conjunto de chaves são diferentes.

## 9. Slots ainda não explicados

Continuam sem derivação confirmada:

```text
slot5
slot14
slot15
```

Eles já estão presentes quando a sessão se torna funcional e, portanto, são candidatos importantes para autenticação, cifragem ou integridade do tráfego 4.7.

## 10. Caminhos testados e bem-sucedidos

### 10.1 Conexão GATT Android direta

**Resultado:** sucesso.

- conexão ao periférico;
- descoberta de FD50;
- identificação das características 0001/0002/0003;
- MTU 247;
- assinatura de `0002`;
- leitura estrutural sem depender do SDK.

### 10.2 Fallback para o SDK

**Resultado:** sucesso e preservado durante toda a investigação.

As tentativas diretas podem falhar/expirar e o SDK continua assumindo a conexão, permitindo validar o T4A e observar o protocolo real.

### 10.3 Introspecção estrutural do ThingClips

**Resultado:** muito bem-sucedido.

Permitiu identificar:

- protocolo 4.7;
- nível NEW;
- controller ativo;
- `ConnectParam`;
- `DeviceInfoRep`/`Rsp`;
- `srand`;
- `authKey`;
- slots de chave;
- helper de envio;
- classes e objetos relevantes apesar da ofuscação.

### 10.4 Comparação criptográfica sem exposição de segredos

**Resultado:** sucesso decisivo.

Produziu as relações exatas:

```text
slot4      = MD5(loginKey)
slot2/12   = MD5(srand)
```

### 10.5 Investigação progressiva e agressiva, mas não atuante

**Resultado:** operacionalmente segura.

Foram ampliadas árvores de candidatos, profundidade de introspecção e janelas de observação sem executar comandos de controle experimentais no T4A.

## 11. Caminhos testados e falhos/descartados

### 11.1 Bootstrap clássico usando `localKey`

**Resultado:** falhou.

A tentativa clássica com `localKey` provocou desconexão imediata (`status=19`).

Conclusão: não usar esse fluxo como handshake do T4A atual.

### 11.2 Bootstrap clássico usando `secKey`

**Resultado:** falhou.

O dispositivo permaneceu silencioso.

### 11.3 Envio de `secKey` raw de 16 bytes

**Resultado:** falhou.

Também não houve resposta útil.

### 11.4 Assumir `DeviceBean.pv=2.2` como protocolo real

**Resultado:** hipótese refutada.

O runtime negociado é 4.7 NEW.

### 11.5 Procurar os slots por igualdade simples em campos armazenados

**Resultado:** insuficiente.

A busca recursiva por campos `byte[]`/strings no grafo não encontrou armazenamento simples correspondente a todas as chaves. Isso sugere derivação sob demanda, encapsulamento ou existência transitória.

### 11.6 Árvore genérica de derivação

Foram testadas famílias e cadeias envolvendo:

- MD5;
- SHA-256 truncado;
- HMAC-SHA256;
- AES/ECB/NoPadding;
- combinações otimistas e pessimistas em múltiplas profundidades.

**Resultado:** encontrou as duas âncoras MD5, mas não explicou slots 5/14/15.

### 11.7 Probe ancorado

Depois da descoberta das âncoras foram exploradas combinações com:

- `MD5(loginKey)`;
- `MD5(srand)`;
- raízes conhecidas (`loginKey`, `loginKeyComplete`, `secretKey`, `devId`, `uuid`, `authKey`, `srand`);
- concatenação em ambas as ordens;
- MD5;
- SHA-256;
- HMAC-MD5;
- HMAC-SHA256;
- AES;
- XOR;
- derivações de profundidade adicional.

**Resultado:** nenhuma relação adicional confirmada para 5/14/15.

Conclusão: ampliar cegamente combinações criptográficas tem retorno decrescente. É mais eficiente identificar o uso real das chaves no caminho de TX/RX.

### 11.8 Procurar uso dos slots no grafo de objetos

Probes de uso/grafo tentaram localizar referências simples aos valores de 5/14/15.

**Resultado:** sem correspondência útil (`matches=0`).

Conclusão: os valores podem ser obtidos por getter no instante do processamento, copiados para objetos transitórios ou consumidos dentro de métodos sem permanecer como campos acessíveis.

### 11.9 Captura de `XRequest` por polling

Foi criada captura de objetos transitórios durante o handshake.

A frequência foi aumentada até aproximadamente:

```text
interval = 5 ms
window   = 9000 ms
```

**Resultado:**

```text
xRequestsVisible = 0
uniqueLogged     = 0
```

Conclusão: reduzir simplesmente o polling para 1 ms não é um próximo passo justificável. O objeto é provavelmente efêmero demais, não fica no grafo percorrido ou o caminho real não depende de uma instância persistente de `XRequest` observável dessa forma.

## 12. Caminho de envio identificado

A investigação estrutural encontrou uma âncora estável no controller `dpdbqdp`:

```text
mSendDataToDeviceHelper -> dpppbbd
```

Essa descoberta muda a estratégia: em vez de continuar procurando objetos transitórios depois que foram criados, o próximo trabalho deve seguir o ponto que **produz, transforma ou consome** os bytes de TX.

A branch já evoluiu para um sampler do caminho ativo de wire (`ThingClips active BLE wire path` / `Use active-path BLE wire sampler`).

## 13. Linha evolutiva dos experimentos

Em termos de conhecimento, a branch percorreu aproximadamente esta sequência:

1. inserir transporte BLE direto antes do SDK;
2. preservar fallback Tuya;
3. confirmar FD50 e topologia GATT;
4. testar hipóteses de bootstrap clássico;
5. descartar `localKey`/`secKey` clássico;
6. inspecionar classes ThingClips;
7. encontrar controller e objetos vivos;
8. determinar protocolo 4.7 NEW;
9. mapear `ConnectParam`, `DeviceInfo` e security raw;
10. correlacionar `srand` e `authKey` com o runtime ofuscado;
11. enumerar slots de chave antes/depois da negociação;
12. mapear getters nomeados aos slots;
13. testar armazenamento simples das chaves;
14. executar árvores de derivação criptográfica;
15. provar `slot4 = MD5(loginKey)`;
16. provar `slot2 = slot12 = MD5(srand)`;
17. explorar derivações ancoradas para 5/14/15;
18. procurar referências/uso dessas chaves no grafo;
19. tentar capturar objetos `XRequest` transitórios;
20. aumentar a amostragem para 5 ms sem capturá-los;
21. localizar `mSendDataToDeviceHelper -> dpppbbd`;
22. migrar a investigação para o caminho ativo de wire.

## 14. O que já pode ser considerado resolvido

### Transporte BLE básico

Resolvido o suficiente para portar ao ESP32:

- serviço;
- características;
- direção de TX/RX;
- CCCD;
- MTU.

### Identificação da família de protocolo

Resolvido:

```text
Tuya/ThingClips BLE protocol 4.7
NEW security
```

### Parte do key schedule

Resolvido:

```text
K4    = MD5(loginKey)
K2/12 = MD5(srand)
```

### Materiais disponíveis

Identificados, embora nem todos tenham função completamente explicada:

- loginKey;
- loginKeyComplete;
- secretKey;
- devId;
- uuid;
- authKey;
- srand.

## 15. O que ainda impede remover o SDK do transporte

Faltam principalmente:

1. formato exato das mensagens 4.7 no wire;
2. sequência completa do handshake NEW;
3. função/derivação dos slots 5, 14 e 15;
4. identificação da chave efetivamente usada em cada etapa/pacote;
5. algoritmo de cifragem/autenticação/integridade e seus parâmetros;
6. framing, sequência, fragmentação/reassembly e validação;
7. codificação/decodificação DPS sobre esse transporte;
8. reprodução independente de uma sessão completa.

## 16. Próximo passo recomendado

Não continuar aumentando polling genérico nem gerar centenas de combinações criptográficas sem evidência.

Prioridade:

```text
dpdbqdp
  -> mSendDataToDeviceHelper
  -> dpppbbd
  -> métodos de preparação/transformação do payload
  -> chamada final que entrega bytes ao GATT
```

Objetivo imediato: obter, sem expor segredos, uma cadeia estrutural do tipo:

```text
mensagem lógica
 -> framing
 -> seleção de slot/chave
 -> transformação criptográfica
 -> frame final
 -> write em FD50/0001
```

Quando esse caminho estiver mapeado, repetir o mesmo raciocínio no RX (`0002`) e então implementar um codec independente.

## 17. Critério para o primeiro protótipo ESP32

O primeiro protótipo independente será considerado viável quando pudermos fornecer ao ESP32:

- endereço/identidade do T4A;
- materiais persistentes necessários;
- algoritmo de handshake documentado;
- geração/validação das chaves de sessão;
- codec de frames 4.7;
- pelo menos uma leitura passiva de telemetria válida.

Somente depois disso devem ser considerados comandos de controle no ESP32.

## 18. Estratégia provável de migração

### Etapa A — Android ainda provisiona

```text
Tuya SDK (somente provisionamento/material persistente)
            |
            v
     armazenamento RideDash
            |
            +--> transporte próprio Android
            +--> ESP32
```

Essa etapa já reduziria fortemente a dependência do SDK no runtime.

### Etapa B — transporte independente

O Android/ESP32 reproduz handshake, sessão, framing e DPS sem usar o SDK para BLE.

### Etapa C — avaliar remoção total do SDK

Só depois de compreender de onde vêm os materiais persistentes de provisionamento será possível decidir se o SDK pode ser removido completamente ou se permanecerá apenas como provisionador.

## 19. Estado atual da branch

Branch:

```text
feature/direct-ble-transport-fallback
```

No momento desta consolidação, o HEAD anterior a este documento era:

```text
97321e605f29f6e2af347483768cf79ec9b26090
Use active-path BLE wire sampler
```

O fallback Tuya continua sendo parte obrigatória do experimento até que o transporte independente complete uma sessão real com segurança.

## 20. Resumo executivo

A investigação já ultrapassou a fase de tentativa cega de BLE.

Sabemos onde conectar, onde escrever, onde receber, qual protocolo está realmente em uso, quais materiais entram na negociação, quais slots existem e duas derivações exatas do key schedule. Também eliminamos vários caminhos falsos: bootstrap clássico, interpretação de `pv=2.2`, busca simples por campos, brute force limitado de transformações e captura de `XRequest` por polling.

O problema agora está concentrado em uma região muito menor: o caminho interno de transformação dos frames 4.7 NEW, especialmente ao redor de `dpdbqdp` e `dpppbbd`.

Esse é o ponto de partida correto para a continuação da engenharia reversa e para chegar ao objetivo final: **transportar conexão e tráfego BLE do T4A para um ESP32 sem depender do SDK Tuya no runtime**.
