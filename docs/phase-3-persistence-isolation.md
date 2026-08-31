# Fase 3 — isolamento de persistência do núcleo

## Objetivo

Remover de `T4ABackend` o acesso direto a `Context` e `SharedPreferences` sem alterar o comportamento do T4A nem perder estado em instalações existentes.

## Estrutura implementada

Foi introduzida a porta neutra:

```text
T4AStateStore
```

Ela representa somente o pequeno conjunto de dados que o backend já persistia:

- último estado conhecido da trava;
- habilitação do auto-lock;
- distância configurada do auto-lock;
- endereço BLE lembrado;
- mínimo e máximo observados da bateria;
- início do ciclo de bateria;
- última leitura live de bateria e horário;
- intervalo mínimo usado para detectar possível recarga.

A implementação Android é:

```text
AndroidT4AStateStore
  -> SharedPreferences("t4a_backend")
```

`T4AApplication` continua sendo o composition root e injeta a implementação Android ao criar `T4ABackend`.

## Compatibilidade in-place

Não existe migração de dados nesta fase. O arquivo e todas as chaves anteriores foram preservados literalmente:

```text
t4a_backend

lock_known
lock_value
auto_lock_distance
auto_lock_distance_level
ble_address
battery_observed_min
battery_observed_max
battery_cycle_started_at
battery_last_live_percent
battery_last_live_at
battery_recharge_min_gap_hours
```

A chave histórica `auto_lock_distance`, apesar do nome, continua armazenando o booleano de habilitação do auto-lock. Ela não foi renomeada para evitar quebra de instalações existentes.

## Alterações de dependência

Antes:

```text
T4ABackend
  -> Context
  -> SharedPreferences
```

Depois:

```text
T4ABackend
  -> T4AStateStore

T4AApplication
  -> AndroidT4AStateStore
  -> SharedPreferences("t4a_backend")
```

O backend continua usando `Handler`, `Looper` e `System.currentTimeMillis()`; esses pontos pertencem à Fase 4 e não foram misturados nesta alteração.

## Comportamento preservado

A fase não altera deliberadamente:

- semântica do DP1 de lock;
- thresholds e histerese do auto-lock;
- reconexão BLE;
- descarte de lock/velocidade/bateria provenientes apenas de `INITIAL/cache`;
- confirmação de comandos por RX;
- regras atuais do ciclo de bateria;
- intervalo padrão de recarga de 1 hora e opções 1/2/4/8 horas;
- UI ou fluxos de tela;
- fronteiras Tuya/BLE;
- política MQTT já corrigida no patch operacional anterior.

Os diagnósticos de reconnect adicionados no PR #22 também foram mantidos e agora consultam `T4AStateStore`.

## Testes

`T4ABackendTest` passa a usar um fake de `T4AStateStore`; portanto suas regras deixam de depender do mecanismo Android de persistência.

`AndroidT4AStateStoreTest` cobre explicitamente:

1. leitura de dados pré-existentes escritos com os nomes antigos;
2. escrita nos mesmos nomes e tipos de preferences;
3. ausência de valores opcionais sem fabricação de estado.

O build também protege a fronteira: `T4ABackend` não pode voltar a importar `Context` ou acessar `SharedPreferences` diretamente.

## Patch operacional anterior

Antes da Fase 3 foi integrado o PR #22 para:

- remover a republicação MQTT de telemetria inalterada a cada 30 segundos;
- instrumentar a transição BLE de conexão e o primeiro RX real do estado da trava após reconnect;
- comprovar por teste que auto-lock desativado não envia comando de unlock por RSSI/reconnect.

Essa instrumentação é particularmente útil para distinguir um eventual desbloqueio produzido pelo aplicativo de uma alteração reportada pelo SDK/firmware durante a conexão.

## Critério de aceite

A Fase 3 está pronta para aceite quando:

- `T4ABackend` não recebe `Context`;
- `T4ABackend` não acessa `SharedPreferences`;
- todas as chaves legadas continuam compatíveis;
- testes e verificações arquiteturais passam no CI;
- o APK instala sobre a versão anterior preservando configurações e estado persistido.
