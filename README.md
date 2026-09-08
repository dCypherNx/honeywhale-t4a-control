# T4A Control para Android

Aplicativo privado para parear, conectar, monitorar e controlar o HoneyWhale T4A. O provisionamento e o transporte BLE continuam atualmente apoiados no Tuya Smart SDK, enquanto sessão persistente, MQTT, Home Assistant e geolocalização já possuem camadas próprias e desacopladas da UI.

## Estado atual

Funcional e implementado:

- autenticação da conta privada T4A Control;
- restauração do T4A previamente pareado;
- descoberta, ativação e remoção do pareamento;
- conexão e reconexão BLE automáticas;
- sessão persistente em `T4ASessionService`, independente do ciclo de vida da `MainActivity`;
- atualização dos DPS recebidos, inclusive mudanças externas;
- comandos absolutos de farol e bloqueio;
- seleção explícita de modo, unidade, piloto automático e tipo de partida;
- bloqueio/desbloqueio automático por proximidade Bluetooth com limiares configuráveis;
- painel de velocidade, bateria, odômetro e percurso;
- separação entre apresentação, sessão, provisionamento e transporte BLE;
- tratamento defensivo provisório da telemetria de bateria a partir dos padrões observados nos logs reais de rodagem;
- opção para manter a tela ligada enquanto a Activity está visível;
- configuração MQTT com TCP/TLS/WS/WSS e senha protegida pelo Android Keystore;
- publicação MQTT **somente de saída**, sem comandos remotos para o T4A;
- telemetria MQTT retained e disponibilidade/LWT;
- Home Assistant MQTT Device Discovery com dispositivo T4A e entidades de telemetria;
- geolocalização Android desacoplada do Tuya, publicada em `t4a/location` e incorporada à telemetria;
- cadência adaptativa de localização fisicamente validada em uso real: parado `20 s / 10 m`, em movimento `3 s / 2 m` como parâmetros desejados do Android;
- `device_tracker` GPS do Home Assistant definido no mesmo dispositivo T4A;
- identificação MQTT estável do T4A a partir do MAC normalizado, independentemente de o SDK fornecer `DC23529724A0` ou `DC:23:52:97:24:A0`;
- build remoto reproduzível com credenciais Tuya restauradas em CI e assinatura Android persistente;
- versionamento SemVer automatizado pela CI/CD, com classificação derivada do nome da branch, versão limpa em `master` e sufixo sequencial curto em builds de feature/fix/major;
- atualização in-place do APK funcional pelo artefato da CI, preservando sandbox, login Tuya e dispositivo previamente vinculado.

Validações ainda úteis, mas que não bloqueiam o encerramento da feature MQTT/geolocalização:

- continuidade específica da localização com a tela bloqueada enquanto a sessão BLE permanece conectada;
- acompanhamento visual do `device_tracker` GPS no Home Assistant durante um deslocamento mais longo.

Ainda não implementado:

- importação de rota compartilhada pelo Google Maps;
- persistência/modelo de percursos com paradas/waypoints ordenados;
- orientação de navegação na tela principal;
- estimativa confiável de SOC da bateria a partir das leituras instáveis recebidas durante carga/descarga dinâmica;
- armazenamento/exportação do bundle mínimo de credenciais para um protocolo BLE próprio;
- funcionamento local independente do SDK/nuvem Tuya;
- OTA e limite de velocidade próprio.

## Arquitetura

- `MainActivity`: cria e atualiza Views, solicita permissões e traduz gestos em intenções. Não importa classes ThingClips/Tuya e não é proprietária da sessão.
- `session/T4ASession`: contrato da UI para observar estado e enviar intenções/comandos.
- `session/T4ASessionService`: proprietário de longa duração de BLE, MQTT e localização; mantém a sessão ativa quando a Activity desaparece.
- `backend/T4ABackend`: máquina de estados e regras do T4A. Recebe provisionamento e transporte separados; não cria o adaptador Tuya.
- `backend/T4AProvisioner`: fronteira substituível para conta, inventário, descoberta, ativação e desvinculação.
- `backend/T4ATransport`: fronteira substituível para sessão BLE, conexão, DPS, cache e RSSI.
- `backend/T4AContracts`: modelos e callbacks neutros compartilhados pelas duas fronteiras.
- `backend/TuyaT4APlatform`: adaptador temporário que implementa ambas as fronteiras usando ThingClips.
- `backend/T4AState`: snapshot imutável entregue à UI e aos consumidores neutros.
- `backend/mqtt/MqttTelemetryCoordinator`: política de conexão/reconexão MQTT, heartbeat, disponibilidade, telemetria, localização e discovery.
- `backend/mqtt/HomeAssistantDiscovery`: gera o Device Discovery sem depender de Android ou Paho.
- `backend/location/LocationSnapshot`: representação geográfica neutra compartilhável com a futura navegação.
- `location/AndroidLocationProvider`: único adaptador Android de localização; não conhece Tuya nem Paho.
- `mqtt/PahoMqttTransport`: único adaptador Eclipse Paho.
- `T4AApplication`: ponto de composição que escolhe implementações concretas entregues ao runtime.
- `backend/T4ASdk`: bootstrap e encerramento do SDK enquanto o adaptador Tuya continuar presente.

O build executa `verifyTuyaBoundary` antes da compilação e falha se uma classe de domínio voltar a importar ThingClips ou se `T4ABackend` tentar criar diretamente o adaptador Tuya. O GitHub Actions repete a verificação em um checkout limpo em cada PR e push para `master`, garantindo também que os contratos separados estejam no commit. Uma futura implementação BLE própria deverá implementar somente `T4ATransport`; o provisionamento Tuya poderá permanecer separado, sem alterar a UI nem as regras de estado em `T4ABackend`.

## Premissas de comandos e estados

- Todo comando escolhido pelo usuário deve ser enviado ao T4A, mesmo quando o valor solicitado for igual ao valor exibido. A interface não deve suprimir, converter em *toggle* nem considerar redundante uma ação absoluta.
- O envio aceito pelo SDK confirma somente a transmissão do comando. Não confirma que o T4A assumiu o estado solicitado.
- Exceto pelo bloqueio descrito abaixo, a interface só deve alterar um estado depois de receber o respectivo valor do T4A. Não deve presumir nem antecipar o resultado de um comando.
- Farol, unidade milha/km e velocidade podem mudar fisicamente no T4A. O aplicativo deve sempre substituir o valor exibido pelo estado mais recente recebido do dispositivo.
- O bloqueio é comandado somente pelo aplicativo. O aplicativo deve persistir a última ação de bloquear ou desbloquear e usá-la como estado conhecido do bloqueio.
- A leitura de `blelock_switch` entregue no `INITIAL` é sistematicamente falsa e informa o estado equivalente a desbloqueado mesmo quando o T4A permanece fisicamente bloqueado. Esse valor de `INITIAL` nunca deve sobrescrever a última ação de bloqueio lembrada pelo aplicativo.
- Uma resposta `RX` posterior associada ao comando de bloqueio atual deve ser registrada para diagnóstico e pode confirmar a ação atual, sem transformar o valor incorreto do `INITIAL` em verdade local.

Para os DPS comuns, o backend nunca informa sucesso alterando o estado local: o valor exibido muda apenas quando chega uma atualização recebida do dispositivo ou do cache atualizado pelo SDK. O bloqueio é a exceção deliberada, pois seu estado conhecido deriva da última ação persistida pelo aplicativo e desconsidera o `INITIAL` sabidamente falso.

## MQTT, Home Assistant e geolocalização

A feature MQTT/geolocalização foi encerrada após validação física no T4A/S25. O runtime mantém as seguintes propriedades:

- MQTT 3.1.1, retained QoS 1, LWT e status `online`/`offline`;
- transporte TCP, TLS, WS e WSS, com `wss://mqtt.jurgensen.net:443/mqtt` validado fisicamente;
- nenhum `subscribe()` e nenhum controle remoto do T4A via MQTT;
- valores DPS desconhecidos são omitidos da telemetria em vez de fabricados como `0`, `false` ou string vazia;
- Home Assistant Device Discovery cria um único dispositivo estável a partir do MAC normalizado;
- o `serial_number` do Discovery usa representação canônica com dois pontos, evitando republicação apenas porque o SDK mudou a formatação textual do MAC;
- `t4a/location` publica o último fix GPS aceito;
- a posição também é incorporada a `t4a/telemetry`;
- em movimento são publicados, quando disponíveis, `gps_speed_kmh`, `bearing_deg` e `altitude_m`;
- `LocationSnapshot` permanece a fonte neutra que será reutilizada pela futura navegação, sem criar uma segunda pilha de localização.

O teste real de 29/08/2026 mostrou a precisão GPS convergindo de um primeiro fix amplo para aproximadamente 3,8–15 m em boa parte do deslocamento, com atualizações de posição durante aceleração, frenagem e mudança de modo do T4A. Esses resultados são suficientes para encerrar a implementação da feature; ajustes finos de filtro de posição poderão ser feitos quando a camada de navegação fornecer critérios concretos de uso.

## Telemetria de bateria: achados dos testes reais

Os logs de viagens contínuas mostraram que `battery_percentage` não pode ser tratado como um SOC absoluto sem validação:

- durante a rodagem existem leituras `RX` abaixo de 100% com forte variação sob carga;
- ocorrem `RX=100` espúrios intercalados entre leituras sub-100, inclusive durante aceleração, portanto esses valores não podem restaurar o SOC depois que a sessão já observou uma leitura válida abaixo de 100%;
- em repouso, a leitura pode recuperar muitos pontos percentuais, evidenciando *voltage sag* relevante;
- um `INITIAL` de conexão fria pode trazer bateria stale/default, como 100%, mesmo quando a bateria real já está parcialmente descarregada;
- `INITIAL/cache` não devem estabelecer bateria por conta própria;
- o raw log permanece intocado para permitir nova calibração do algoritmo com percursos futuros.

O teste real de 29/08/2026 reforçou que a solução atual ainda é apenas defensiva: durante o percurso foram observadas sequências como `100 → 76 → 74 → 72 → 69 → 95` e novas oscilações grandes associadas à carga. A correção definitiva **não faz parte da feature MQTT/geolocalização**. O próximo trabalho será aberto separadamente em uma branch `fix/*`, classificada automaticamente como `patch`, para investigar e definir a semântica de uma bateria confiável antes de alterar o valor publicado como `battery_percent`.

Ainda não há estimativa própria de SOC, suavização por janela validada, curva de descarga calibrada nem cálculo de autonomia restante. Esses itens exigem análise específica e novos ciclos de uso.

## Investigação de portabilidade Tuya e autenticação BLE

A investigação de agosto de 2026 busca determinar quais dados realmente precisam ser obtidos no Android para que, no marco final, o ESP32 consiga estabelecer uma sessão BLE válida com o T4A sem depender continuamente do SDK ou da nuvem Tuya.

### Identidade do aplicativo Tuya

Foi testado um APK de CI paralelo usando `applicationId br.com.t4acontrol.ci`, mantendo o mesmo AppKey, AppSecret e arquivos de segurança Tuya. O APK instalou ao lado da aplicação funcional, mas o SDK encerrou a inicialização com erro de incompatibilidade entre `app key`, `app secret` e `packageName`. A tentativa demonstrou experimentalmente que o pacote Android faz parte da identidade registrada da aplicação Tuya.

Por isso o projeto voltou a usar exclusivamente:

```text
br.com.t4acontrol
```

O build remoto não deve aplicar `applicationIdSuffix` enquanto utilizar as credenciais atuais da Tuya. A versão 1.1.1 / `versionCode 12` formaliza essa correção histórica do baseline.

### Baseline de assinatura e depuração confirmado

O APK funcional instalado foi comparado com o `debug.keystore` usado originalmente no desenvolvimento. Ambos apresentam o mesmo certificado:

```text
alias: androiddebugkey
SHA-256: DA:4F:06:64:EC:97:A6:F2:D3:6E:31:1F:FA:E4:89:57:CF:97:24:47:A9:C0:1E:42:C3:98:7D:3D:64:B6:6D:10
```

Uma cópia dedicada desse mesmo keystore passou a ser a identidade persistente da CI. O artefato histórico `v1.1.1` / `versionCode 12` gerado pela esteira foi verificado com o mesmo `applicationId` e o mesmo fingerprint e instalado com `adb install -r` sobre a instalação funcional.

Após a atualização:

- o aplicativo iniciou normalmente;
- o `ThingSdk` inicializou com sucesso;
- o `t_cdc.tcfg` foi aceito;
- a sessão Tuya existente continuou autenticada;
- o sandbox em `/data/user/0/br.com.t4acontrol` foi preservado;
- o inventário recuperou o T4A previamente vinculado;
- não ocorreu o erro de incompatibilidade entre AppKey, AppSecret e packageName observado no experimento `.ci`.

Esse artefato da CI é, portanto, o baseline de depuração reproduzível para as próximas análises. A chave privada, suas senhas e seu conteúdo nunca devem ser adicionados ao repositório; somente o fingerprint público é documentado aqui.

### Acesso não destrutivo ao sandbox Android

A instalação funcional atual é `DEBUGGABLE`. Usando ADB e `run-as br.com.t4acontrol` foi possível inspecionar seu sandbox sem root, sem limpar dados, sem desparear o T4A e sem alterar a sessão Tuya existente.

Foram encontrados, entre outros:

```text
files/thingmmkv/GLOBAL_SECURITY_KEY
files/thingmmkv/Login_user_main
files/thingmmkv/Login_user_other
files/thingmmkv/MMMANAGER_THING_KV_1
files/thingmmkv/ble_business_data
files/thingmmkv/ble_channel_cache_data_V2
files/thingmmkv/ble_channel_cache_data_V4
files/thingmmkv/key_network_cache
files/thingmmkv/preferences_global_key
```

Também existem preferências próprias do T4A Control. `t4a_secure_credentials.xml` contém somente os campos `ciphertext` e `iv`; `t4a_backend.xml` mantém estado do backend e `t4a_settings.xml` mantém preferências de UI/funcionamento. Esses arquivos próprios não devem ser confundidos com o armazenamento interno da Tuya.

### Separação observada entre login e sessão BLE

Foi criada uma linha de base SHA-256 dos principais arquivos MMKV e executada uma sequência controlada de testes.

Resultados observados:

- conectar e operar o T4A não alterou `GLOBAL_SECURITY_KEY`, `Login_user_main`, `Login_user_other`, `MMMANAGER_THING_KV_1`, `ble_channel_cache_data_V2`, `ble_channel_cache_data_V4` nem `key_network_cache`;
- uma reconexão real ao T4A alterou somente `ble_business_data` e `preferences_global_key` entre os arquivos monitorados;
- mantendo a conexão ativa por 30 segundos sem interação, nenhum dos dois arquivos mudou;
- enviar um comando normal de farol também não alterou nenhum dos dois;
- nova reconexão voltou a alterar os mesmos dois arquivos.

Isso fornece evidência experimental de que a sessão de login Tuya e o estado atualizado na criação/recriação da sessão BLE são persistências distintas.

### Estrutura das alterações MMKV

Foram comparadas cópias binárias antes e depois de uma reconexão, sem publicar o conteúdo dos registros.

`ble_business_data`:

```text
arquivo:                 4096 bytes
used size antes:         1624
used size depois:        1653
crescimento:               29 bytes
bytes diferentes:          28
região principal nova:  1628..1656
```

`preferences_global_key`:

```text
arquivo:                 4096 bytes
used size antes:         1094
used size depois:        1118
crescimento:               24 bytes
bytes diferentes:          25
região principal nova:  1098..1121
```

O crescimento coincide exatamente com a área anexada após o `used size` anterior. Portanto a reconexão não está recriptografando ou reescrevendo o arquivo MMKV inteiro; ela acrescenta pequenos registros persistentes. Uma busca ASCII no novo bloco de `ble_business_data` encontrou apenas o fragmento `fh1`, insuficiente para atribuir significado ao registro. A partir deste ponto não é seguro inferir a estrutura por tentativa de decodificação manual.

Nenhum valor potencialmente secreto desses arquivos deve ser registrado no README, em issues, logs públicos ou commits.

### Material de dispositivo observado no inventário do SDK

Com o baseline de CI instalado e o sandbox preservado, o log de inicialização do `ThingSdk` mostrou que a resposta de inventário do dispositivo já contém metadados e material de segurança associados ao T4A. Entre os campos observados estão:

```text
devId
productId
uuid
mac
localKey
secKey
communicationModes
btScyChannel
```

Os valores de `localKey` e `secKey`, assim como identificadores de conta/sessão, são considerados segredos e não devem ser publicados no repositório nem mantidos em raw logs compartilhados. Essa descoberta muda a prioridade da investigação: antes de tentar decodificar MMKV manualmente, deve-se correlacionar o material já exposto pela API do SDK com a autenticação e o handshake BLE.

O objetivo continua sendo descobrir o conjunto mínimo de dados de dispositivo necessário para uma sessão BLE válida. Não há evidência, neste ponto, de que o token de login Tuya precise ou deva ser transportado para o ESP32.

## Roadmap da análise Tuya/BLE

A investigação seguirá esta ordem, mantendo o aplicativo funcional como referência e evitando ações destrutivas:

1. **Baseline CI/debug — concluído.** `br.com.t4acontrol`, assinatura original, artefato `DEBUGGABLE`, instalação in-place e preservação do sandbox foram validados experimentalmente.
2. **Capturar uma representação neutra do inventário Tuya.** Instrumentar somente a fronteira `TuyaT4APlatform` para registrar de forma controlada nomes/tipos e metadados necessários do T4A, sem despejar segredos em Logcat. `localKey` e `secKey` devem ser manipulados como segredo e, quando necessário, armazenados somente em meio seguro.
3. **Correlacionar inventário com a abertura da sessão BLE.** Registrar eventos e parâmetros na fronteira imediatamente antes/depois de conexão, autenticação e estabelecimento do canal, preservando o raw log BLE original e evitando alterar comportamento do SDK.
4. **Determinar o papel de `localKey` e `secKey`.** Verificar experimentalmente se um ou ambos participam diretamente do handshake, derivam outra chave ou servem somente ao provisionamento/cloud. Não assumir sem evidência qual campo é a chave BLE final.
5. **Separar material persistente de material efêmero.** Repetir conexões, force-stop e cenários offline para identificar quais parâmetros permanecem válidos e quais são renovados a cada sessão.
6. **Usar MMKV como evidência complementar.** Somente se a instrumentação do SDK não explicar completamente a sessão, ler `ble_business_data`, `preferences_global_key` e respectivos `.crc` com implementação MMKV compatível e correlacionar seus pequenos registros de reconexão com os eventos observados.
7. **Testar portabilidade entre instalações/dispositivos Android.** Validar o mesmo `applicationId`/identidade Tuya em outro dispositivo e observar se o T4A previamente vinculado é restaurado após login sem novo pareamento físico, distinguindo vínculo de conta de material local do aparelho.
8. **Definir um bundle neutro de provisionamento.** Exportar somente identificadores, versões de protocolo e segredos mínimos comprovadamente necessários ao transporte BLE próprio, sem transportar token de conta Tuya para o ESP32.
9. **Implementar transporte BLE independente no Android.** Criar uma implementação de `T4ATransport` sem ThingClips, mantendo inicialmente o `T4AProvisioner` Tuya para obtenção/renovação do material necessário.
10. **Migrar o transporte para ESP32.** Usar o mesmo bundle neutro para estabelecer a sessão BLE diretamente entre ESP32 e T4A. O objetivo final é `ESP32 ⇄ BLE ⇄ T4A`, sem dependência operacional da nuvem Tuya.

A hipótese de trabalho é deliberadamente mais restrita do que "copiar o login Tuya": o alvo é descobrir **qual material de autorização o provisionamento Android entrega ou deriva para permitir uma sessão BLE válida com um T4A já vinculado**.

## Marco 2: estado e próximos passos

O Marco 2 tem quatro partes explícitas:

1. **Sessão persistente — concluída e fisicamente validada.** `T4ASessionService` é proprietário do backend; recriar/bloquear a UI não cria uma nova sessão BLE apenas por causa da Activity.
2. **MQTT + Home Assistant — concluído e fisicamente validado.** WSS, retained telemetry, disponibilidade/LWT e Device Discovery estão operacionais. MQTT permanece estritamente publish-only e não controla o T4A.
3. **Geolocalização — concluída e fisicamente validada no fluxo principal.** `AndroidLocationProvider` produz `LocationSnapshot`, publica `t4a/location` e alimenta a telemetria. O teste real confirmou aquisição adaptativa durante deslocamento. A continuidade específica com tela bloqueada e a visualização do `device_tracker` em percurso longo permanecem como validações complementares, não como desenvolvimento pendente desta feature.
4. **Navegação — pendente e ainda pertencente ao Marco 2.** O APK deve receber uma rota compartilhada pelo Google Maps, preservar paradas/waypoints ordenados, permitir salvar percursos e mostrar na primeira tela a próxima instrução de direção com distância restante.

A feature MQTT/geolocalização pode ser integrada à `master`. Antes de iniciar a navegação, o próximo trabalho deliberadamente separado será um **fix da bateria**, pois os logs reais mostram que a semântica de `battery_percentage` é mais complexa do que uma filtragem pontual.

A navegação deverá reutilizar `LocationSnapshot`; não deve criar uma segunda pilha de GPS. Também não deve introduzir controle remoto do patinete por MQTT.

Critérios de encerramento completo do Marco 2:

- MQTT/Discovery e localização real publicados de forma coerente;
- recepção de URL/rota compartilhada pelo Google Maps;
- modelo persistente de percurso com waypoints/paradas ordenados;
- progresso da rota calculado a partir da posição corrente;
- ícone de manobra + distância até a próxima instrução exibidos na tela principal;
- teste físico de um percurso curto com navegação ativa.

## Compilação, CI e depuração por ADB

- Android Gradle Plugin: 9.3.2
- Gradle Wrapper: 9.7.1
- Java: 17 no código
- compileSdk: 36
- targetSdk: 35
- Tuya Smart SDK: 7.8.0
- applicationId: `br.com.t4acontrol`
- `versionName` e `versionCode` dos APKs de CI são injetados pelo workflow `Build APK`.

O arquivo privado `tuya.properties` deve conter `TUYA_APP_KEY` e `TUYA_APP_SECRET`. O APK local de desenvolvimento continua usando o fallback histórico `versionName 1.1.1` / `versionCode 12` e é gerado em `app/build/outputs/apk/debug/app-debug.apk`.

### Versionamento CI/CD

O workflow `.github/workflows/build-apk.yml` é a autoridade para versionar APKs produzidos pela CI/CD. A versão estável canônica é representada por uma tag Git `vMAJOR.MINOR.PATCH`. Enquanto ainda não existir uma tag estável igual ou superior ao baseline atual, `1.1.1` funciona somente como valor de bootstrap; depois disso a maior tag SemVer passa a ser a fonte de verdade.

A classificação SemVer é definida **pela branch**, não pela existência de PR nem por labels:

- `fix/*`, `bugfix/*` ou `hotfix/*` → `patch`;
- `feature/*` → `minor`;
- `major/*` ou `breaking/*` → `major`.

Assim, `feature/mqtt-telemetry` é obrigatoriamente uma alteração `minor`. O workflow `Architecture boundary` valida essa classificação quando a branch participa de um PR para `master`, mas o `Build APK` usa a mesma regra diretamente em qualquer execução manual feita na própria branch.

Regras de geração:

- build manual em branch: calcula primeiro a versão SemVer projetada conforme a classe da branch e acrescenta o indicador sequencial `-f<run_number>`; por exemplo, partindo de `1.1.1`, uma `feature/*` gera `1.2.0-f81` e uma `fix/*` gera `1.1.2-f82`;
- merge em `master`: o `Build APK` identifica a branch de origem do PR associado ao commit de merge, reaplica sua classificação e gera o incremento SemVer puro, sem sufixo;
- depois que o APK de `master` compila com sucesso, o CI cria a tag estável `v<versionName>` apontando para exatamente aquele commit;
- rerun do mesmo commit de `master` é idempotente: se a tag estável já aponta para o commit, a versão é reutilizada e não sofre novo incremento;
- push direto para `master` sem origem classificável e sem tag estável correspondente falha na resolução de versão, evitando avanço não classificado;
- execução manual em `master` reutiliza a versão estável corrente e não cria um incremento artificial;
- `versionCode` usa `100000 + GITHUB_RUN_NUMBER`, permanecendo numérico e crescente entre novas execuções do workflow;
- um rerun da mesma execução mantém o mesmo `versionCode` porque continua sendo o mesmo run lógico;
- o `applicationId` permanece exatamente `br.com.t4acontrol`; versionamento nunca deve usar `applicationIdSuffix`.

Exemplo da promoção da branch atual:

```text
base atual:  1.1.1
feature:     1.2.0-f<run_number>
merge:       1.2.0
stable:      v1.2.0
```

Não se deve editar `versionName`/`versionCode` no Gradle para produzir um novo APK de CI. O Gradle mantém apenas os fallbacks necessários para build local; a identidade dos APKs distribuídos é calculada pelo CI/CD.

O workflow restaura os inputs privados da Tuya, resolve e injeta a versão, executa `verifyTuyaBoundary`, assina o build com a chave persistente configurada nos Secrets e publica um APK **debuggable** com o mesmo `applicationId` registrado na Tuya.

Artefatos esperados:

```text
master:
  versionName:      <versão SemVer promovida>
  artifact/APK:     T4A-Control-<versão>-debug-<short SHA>.apk

branch:
  versionName:      <versão SemVer projetada>-f<run_number>
  artifact/APK:     T4A-Control-<versão>-f<run_number>-debug-<short SHA>.apk

Stable tag:         v<MAJOR>.<MINOR>.<PATCH>
Release tag móvel:  latest-debug
Release asset:      T4A-Control-debug.apk
```

O release `latest-debug` mantém o nome estável do asset para não quebrar o fluxo de instalação, mas seu título e suas notas registram `versionName`, `versionCode`, classificação de incremento, PR de origem quando aplicável, branch e commit exatos.

Essa configuração foi escolhida para permitir que um APK vindo diretamente da esteira seja instalado e posteriormente investigado por ADB a partir do ChatGPT para Windows, mantendo código, commit e binário rastreáveis.

### Assinatura canônica da CI

A migração para o APK da CI foi concluída sem desinstalação. O APK funcional e o artefato remoto usam o mesmo certificado Android Debug, cujo fingerprint SHA-256 público está documentado acima. A CI deve continuar usando essa mesma chave em todos os builds futuros que pretendam atualizar `br.com.t4acontrol` preservando os dados existentes.

Regras permanentes:

1. não gerar uma nova chave para substituir a assinatura atual de `br.com.t4acontrol`;
2. manter backup privado da chave canônica fora do repositório;
3. validar `applicationId` e fingerprint antes de qualquer mudança futura na estratégia de assinatura;
4. usar `adb install -r` para atualizar o baseline de depuração sem apagar o sandbox;
5. nunca publicar keystore, senhas, AppKey, AppSecret, conteúdo MMKV, `localKey`, `secKey`, tokens de conta ou chaves derivadas.

Secrets, AppKey, AppSecret, conteúdo MMKV, chaves de dispositivo, chaves derivadas e outros dados de autenticação nunca devem ser adicionados ao repositório público.
