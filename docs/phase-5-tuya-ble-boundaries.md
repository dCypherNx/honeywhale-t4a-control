# Fase 5 — consolidação das fronteiras Tuya/BLE

## Objetivo

Preparar a substituição futura do transporte runtime Tuya por uma implementação BLE própria sem alterar a UI, as regras centrais do T4A, a persistência ou o provisionamento atualmente validado.

A Fase 5 não remove o SDK Tuya. Ela garante que o SDK permaneça atrás de adaptadores substituíveis e que `T4AProvisioner` possa continuar Tuya enquanto `T4ATransport` evolui de forma independente.

## Auditoria do contrato `T4ATransport`

O contrato atual contém apenas capacidades efetivamente consumidas pelo runtime:

| Capacidade | Uso atual | Decisão |
| --- | --- | --- |
| `attach(Device, DeviceListener)` | associa a sessão runtime e recebe DPS/conectividade | manter |
| `detach()` | encerra a associação ao trocar/remover dispositivo | manter |
| `connect(Device)` | solicita conexão BLE do dispositivo pareado | manter |
| `isConnected(deviceId)` | reconexão, estado e manutenção periódica | manter |
| `cachedDevice(deviceId)` | obtém snapshot conhecido para refresh não autoritativo | manter por enquanto; uma implementação BLE própria pode manter seu próprio cache neutro |
| `publish(deviceId, dps, callback)` | envia comandos DP do domínio T4A | manter |
| `readRssi(mac, callback)` | auto-lock e indicador de proximidade | manter |
| `destroy()` | encerra recursos pertencentes ao transporte | manter |

Nenhuma assinatura expõe classe ThingClips/Tuya. `Device`, `DeviceListener`, callbacks, DPS e RSSI permanecem modelos do próprio projeto.

Não foi adicionada nenhuma capacidade especulativa para a futura implementação BLE.

## Acoplamento encontrado

A separação das interfaces já existia, mas a implementação concreta ainda possuía um vínculo operacional importante:

```text
TuyaT4APlatform.remove(deviceId)
  -> activeDevice
  -> criado somente por T4ATransport.attach(...)
```

Isso significava que, ao substituir apenas `T4ATransport`, o provisionamento Tuya deixaria de conseguir remover o vínculo do dispositivo porque não existiria mais um `activeDevice` criado pelo transporte Tuya.

Esse acoplamento contrariava diretamente o resultado esperado da Fase 5.

## Primeiro corte implementado

Foi introduzido:

```text
TuyaT4AProvisioner implements T4AProvisioner
```

O adaptador mantém o comportamento validado de conta, casa, descoberta e ativação delegando essas operações ao `TuyaT4APlatform` existente.

A remoção do vínculo, entretanto, abre seu próprio handle Tuya por `deviceId` e não depende de nenhuma sessão runtime anexada.

O composition root passa a construir as fronteiras separadamente:

```text
T4AProvisioner -> TuyaT4AProvisioner
T4ATransport   -> TuyaT4APlatform
```

Assim, o backend já executa hoje com objetos de provisionamento e transporte distintos. A futura troca do runtime fica localizada no composition root:

```text
T4AProvisioner -> TuyaT4AProvisioner
T4ATransport   -> implementação BLE própria
```

## Proteção automática

`verifyTuyaBoundary` foi reforçado para:

- exigir a presença das duas fronteiras e do novo adaptador de provisionamento;
- continuar bloqueando imports ThingClips/Tuya fora dos adaptadores permitidos;
- rejeitar referências a `Tuya`, `ThingClips`, `ThingHomeSdk` ou `com.thingclips.*` dentro de `T4AContracts`, `T4AProvisioner` e `T4ATransport`;
- impedir que `T4ABackend` construa qualquer adaptador Tuya.

## Critério de aceite já coberto por testes

`T4ABackendTest` usa `FakeProvisioner` e `FakeTransport` como objetos distintos e executa os principais fluxos sem qualquer classe Tuya: restauração, descoberta, pareamento, conexão/reconexão, publicação de comandos, confirmação por RX, lock, auto-lock, RSSI e bateria.

Isso já satisfaz o critério estrutural definido no plano para uma fake implementation de `T4ATransport`.

## Comportamento que não deve mudar

Este corte não pretende alterar:

- login Tuya;
- descoberta e pareamento;
- conexão BLE runtime;
- DPS e sua semântica;
- lock/auto-lock;
- bateria;
- MQTT;
- UI;
- SharedPreferences existentes;
- `applicationId = br.com.t4acontrol`;
- inicialização global do SDK Tuya.

## Validação da fase

Antes de considerar este primeiro corte aceito devem passar:

1. `verifyTuyaBoundary`;
2. testes unitários de `backend` e `app`;
3. build debug;
4. instalação in-place;
5. teste físico mínimo de restauração da sessão, conexão, comando e remoção/novo pareamento.

A remoção/novo pareamento é particularmente importante neste corte porque foi o ponto de acoplamento concreto corrigido.
