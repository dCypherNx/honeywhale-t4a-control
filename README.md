# T4A Control para Android

Aplicativo privado para parear, conectar, monitorar e controlar o HoneyWhale T4A pelo Tuya Smart SDK.

## Estado atual (v1.1.1)

Funcional e implementado:

- autenticação da conta privada T4A Control;
- restauração do T4A previamente pareado;
- descoberta, ativação e remoção do pareamento;
- conexão e reconexão BLE automáticas;
- atualização dos DPS recebidos, inclusive mudanças externas;
- comandos absolutos de farol e bloqueio;
- seleção explícita de modo, unidade, piloto automático e tipo de partida;
- painel de velocidade, bateria, odômetro e percurso;
- separação entre apresentação, provisionamento e transporte BLE;
- tratamento defensivo da telemetria de bateria a partir dos padrões observados nos logs reais de rodagem;
- opção para manter a tela ligada enquanto a Activity está visível;
- build remoto reproduzível com credenciais Tuya restauradas em CI e assinatura Android persistente.

Ainda não implementado ou validado:

- MQTT e discovery para Home Assistant;
- armazenamento de chaves de um protocolo BLE próprio no Android Keystore;
- funcionamento local independente do SDK/nuvem Tuya;
- serviço em primeiro plano para conexão persistente com o aplicativo encerrado ou com a UI indisponível;
- OTA, limite de velocidade e desbloqueio automático por proximidade.

## Arquitetura

- `MainActivity`: cria e atualiza Views, solicita permissões e traduz gestos em intenções. Não importa classes ThingClips/Tuya.
- `backend/T4ABackend`: máquina de estados e regras do T4A. Recebe provisionamento e transporte separados; não cria o adaptador Tuya.
- `backend/T4AProvisioner`: fronteira substituível para conta, inventário, descoberta, ativação e desvinculação.
- `backend/T4ATransport`: fronteira substituível para sessão BLE, conexão, DPS, cache e RSSI.
- `backend/T4AContracts`: modelos e callbacks neutros compartilhados pelas duas fronteiras.
- `backend/TuyaT4APlatform`: adaptador temporário que implementa ambas as fronteiras usando ThingClips.
- `T4AApplication`: ponto de composição que escolhe as implementações concretas entregues ao backend.
- `backend/T4AState`: snapshot imutável entregue à UI; evita que Views acessem objetos mutáveis do SDK.
- `backend/T4ASdk`: bootstrap e encerramento do SDK enquanto o adaptador Tuya continuar presente.

O build executa `verifyTuyaBoundary` antes da compilação e falha se uma classe de domínio voltar a importar ThingClips ou se `T4ABackend` tentar criar diretamente o adaptador Tuya. O GitHub Actions repete a verificação em um checkout limpo em cada PR e push para `master`, garantindo também que os contratos separados estejam no commit. Uma futura implementação BLE própria deverá implementar somente `T4ATransport`; o provisionamento Tuya poderá permanecer separado, sem alterar a UI nem as regras de estado em `T4ABackend`.

## Premissas de comandos e estados

- Todo comando escolhido pelo usuário deve ser enviado ao T4A, mesmo quando o valor solicitado for igual ao valor exibido. A interface não deve suprimir, converter em *toggle* nem considerar redundante uma ação absoluta.
- O envio aceito pelo SDK confirma somente a transmissão do comando. Não confirma que o T4A assumiu o estado solicitado.
- Exceto pelo bloqueio descrito abaixo, a interface só deve alterar um estado depois de receber o respectivo valor do T4A. Não deve presumir nem antecipar o resultado de um comando.
- Farol, unidade milha/km e velocidade podem mudar fisicamente no T4A. O aplicativo deve sempre substituir o valor exibido pelo estado mais recente recebido do dispositivo.
- O bloqueio é comandado somente pelo aplicativo. O aplicativo deve persistir a última ação de bloquear ou desbloquear e usá-la como estado conhecido do bloqueio.
- Ao reconectar, o T4A sempre informa bloqueio como desbloqueado, inclusive quando permanece fisicamente bloqueado. Esse valor inicial de reconexão não deve sobrescrever a última ação de bloqueio lembrada pelo aplicativo.
- Uma resposta posterior que possa ser associada ao comando de bloqueio atual deve ser registrada para diagnóstico, sem transformar o valor incorreto de reconexão em verdade local.

Para os DPS comuns, o backend nunca informa sucesso alterando o estado local: o valor exibido muda apenas quando chega uma atualização recebida do dispositivo ou do cache atualizado pelo SDK. O bloqueio é a exceção deliberada, pois seu estado conhecido deriva da última ação persistida pelo aplicativo.

## Telemetria de bateria: achados dos testes reais

Os logs de duas viagens contínuas mostraram que `battery_percentage` não pode ser tratado como um SOC absoluto sem validação:

- durante a rodagem existem leituras `RX` fisicamente plausíveis abaixo de 100%, com tendência de descarga e forte variação sob carga;
- ocorrem `RX=100` espúrios intercalados entre leituras sub-100, inclusive durante aceleração, portanto esses valores não podem restaurar o SOC depois que a sessão já observou uma leitura válida abaixo de 100%;
- em repouso, as leituras sob carga recuperam vários pontos percentuais, evidenciando *voltage sag* relevante;
- um `INITIAL` de conexão fria pode trazer bateria stale/default, como 100%, mesmo quando a bateria real já está parcialmente descarregada;
- um `INITIAL` de retomada pode ser válido e repetir exatamente a última leitura `RX`, como observado com 74% durante a viagem;
- por isso `INITIAL/cache` não estabelecem bateria por conta própria: somente confirmam o último valor `RX` confiável já conhecido pela mesma instância do backend;
- o raw log permanece intocado para permitir nova calibração do algoritmo com percursos futuros.

Ainda não há estimativa própria de SOC, suavização por janela, curva de descarga calibrada nem cálculo de autonomia restante. Esses itens exigem mais ciclos de uso e pertencem à evolução de telemetria.

## Investigação de portabilidade Tuya e autenticação BLE

A investigação de agosto de 2026 busca determinar quais dados realmente precisam ser obtidos no Android para que, no marco final, o ESP32 consiga estabelecer uma sessão BLE válida com o T4A sem depender continuamente do SDK ou da nuvem Tuya.

### Identidade do aplicativo Tuya

Foi testado um APK de CI paralelo usando `applicationId br.com.t4acontrol.ci`, mantendo o mesmo AppKey, AppSecret e arquivos de segurança Tuya. O APK instalou ao lado da aplicação funcional, mas o SDK encerrou a inicialização com erro de incompatibilidade entre `app key`, `app secret` e `packageName`. A tentativa demonstrou experimentalmente que o pacote Android faz parte da identidade registrada da aplicação Tuya.

Por isso o projeto voltou a usar exclusivamente:

```text
br.com.t4acontrol
```

O build remoto não deve aplicar `applicationIdSuffix` enquanto utilizar as credenciais atuais da Tuya. A versão 1.1.1 / `versionCode 12` formaliza essa correção.

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

## Roadmap da análise Tuya/BLE

A investigação seguirá esta ordem, mantendo o aplicativo funcional como referência e evitando ações destrutivas:

1. **Estabelecer uma instalação de depuração reproduzível pela CI.** Comparar o certificado da instalação atualmente funcional com o certificado persistente usado pelo GitHub Actions. O mesmo `applicationId` só permite atualização in-place quando as assinaturas também são compatíveis. Não desinstalar a aplicação funcional antes de resolver essa transição.
2. **Ler MMKV com implementação compatível.** Analisar cópias locais de `ble_business_data`, `preferences_global_key` e respectivos `.crc` usando a própria biblioteca MMKV ou um leitor compatível, enumerando nomes/tipos sem publicar valores sensíveis.
3. **Identificar os registros produzidos na reconexão.** Repetir snapshots controlados antes/depois e correlacionar exatamente quais chaves MMKV recebem os blocos de 29 e 24 bytes.
4. **Correlacionar persistência com objetos expostos pelo SDK.** Instrumentar somente `TuyaT4APlatform`/fronteira Tuya para registrar metadados neutros do dispositivo e da sessão, mantendo o raw log BLE original inalterado.
5. **Separar vínculo persistente de material efêmero.** Determinar quais dados sobrevivem ao encerramento do processo, quais são recriados a cada conexão e quais dependem da conta/nuvem.
6. **Testar portabilidade entre instalações/dispositivos.** Depois que a assinatura de CI estiver estabilizada, validar o mesmo `applicationId` em outro dispositivo Android e observar se login, inventário e T4A previamente vinculado podem ser restaurados sem novo pareamento físico.
7. **Definir um bundle neutro de provisionamento.** Exportar somente os identificadores/segredos mínimos realmente necessários ao transporte BLE próprio, em formato controlado pelo projeto, sem transportar token de conta Tuya para o ESP32.
8. **Implementar transporte BLE independente no Android.** Criar uma implementação de `T4ATransport` sem ThingClips, mantendo inicialmente o `T4AProvisioner` Tuya para obtenção/renovação do material necessário.
9. **Migrar o transporte para ESP32.** Usar o mesmo bundle neutro para estabelecer a sessão BLE diretamente entre ESP32 e T4A. O objetivo final é `ESP32 ⇄ BLE ⇄ T4A`, sem dependência operacional da nuvem Tuya.

A hipótese de trabalho é deliberadamente mais restrita do que "copiar o login Tuya": o alvo é descobrir **qual material de autorização o provisionamento Android entrega ou deriva para permitir uma sessão BLE válida com um T4A já vinculado**.

## Marco 2: achados e requisitos já identificados

Os testes de rodagem revelaram requisitos arquiteturais que devem ser tratados no Marco 2, junto com MQTT, geolocalização e navegação:

- a sessão BLE não deve depender do ciclo de vida da `MainActivity`;
- bloquear/desbloquear a tela, trocar temporariamente de Activity ou recriar a UI não deve provocar novo `attach`, nova consulta de inventário ou perda da telemetria já ativa;
- o backend/transport deverá viver em componente de duração maior que a UI, preferencialmente um `Foreground Service` ou uma sessão persistente equivalente, com a `MainActivity` apenas assinando o estado existente;
- MQTT e geolocalização devem continuar funcionando mesmo quando a tela estiver bloqueada e a UI não estiver ativa;
- ao retornar para a UI, o aplicativo deverá reassinar o estado corrente sem reinicializar a sessão BLE e sem introduzir `INITIAL` artificialmente por causa da Activity;
- os testes no Galaxy S25 mostraram bloqueio de segurança por detecção de movimento compatível com possível roubo. `FLAG_KEEP_SCREEN_ON` continua útil contra timeout normal, mas não deve ser considerado mecanismo para impedir esse tipo de bloqueio de segurança;
- o aplicativo deve permanecer funcional mesmo que esse bloqueio externo ocorra: a meta do Marco 2 é manter a conexão/telemetria e tornar o evento transparente para o transporte BLE;
- a investigação futura deverá distinguir claramente `INITIAL` gerado por nova sessão real do T4A de `INITIAL` provocado por reanexação do SDK/backend.

A direção desejada para o Marco 2 é, portanto: **UI descartável, sessão BLE persistente e telemetria independente da tela**.

## Compilação, CI e depuração por ADB

- Android Gradle Plugin: 9.3.2
- Gradle Wrapper: 9.7.1
- Java: 17 no código
- compileSdk: 36
- targetSdk: 35
- Tuya Smart SDK: 7.8.0
- applicationId: `br.com.t4acontrol`
- versionCode: 12
- versionName: 1.1.1

O arquivo privado `tuya.properties` deve conter `TUYA_APP_KEY` e `TUYA_APP_SECRET`. O APK local de desenvolvimento é gerado em `app/build/outputs/apk/debug/app-debug.apk`.

O workflow `.github/workflows/build-apk.yml` restaura os inputs privados da Tuya, executa `verifyTuyaBoundary`, assina o build com a chave persistente configurada nos Secrets e publica um APK **debuggable** com o mesmo `applicationId` registrado na Tuya.

Artefatos esperados:

```text
GitHub Actions: t4a-control-debug-<commit SHA>
APK:            T4A-Control-debug-<short SHA>.apk
Release tag:    latest-debug
Release asset:  T4A-Control-debug.apk
```

Essa configuração foi escolhida para permitir que um APK vindo diretamente da esteira seja instalado e posteriormente investigado por ADB a partir do ChatGPT para Windows, mantendo código, commit e binário rastreáveis.

### Restrição de assinatura na migração para o APK da CI

O `applicationId` correto não é suficiente para substituir uma instalação existente. Android exige também que o APK novo seja assinado por um certificado compatível com o APK instalado. A instalação funcional atual não deve ser removida até que seu certificado seja comparado com o certificado persistente da CI.

A sequência segura para adotar o APK de CI como baseline de depuração é:

1. extrair/imprimir somente os fingerprints dos certificados do APK atualmente instalado e do artefato CI;
2. se coincidirem, instalar o artefato como atualização normal;
3. se forem diferentes, decidir a estratégia de migração antes de qualquer desinstalação, pois remover o aplicativo apaga o sandbox e pode invalidar material protegido pelo Android Keystore;
4. depois da migração, manter a mesma chave de assinatura CI para todos os builds de depuração futuros.

Secrets, AppKey, AppSecret, conteúdo MMKV, chaves derivadas e outros dados de autenticação nunca devem ser adicionados ao repositório público.
