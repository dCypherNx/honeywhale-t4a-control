# Plano de ação — endurecimento arquitetural do T4A Control

## Objetivo

Consolidar a arquitetura atual antes da próxima expansão funcional, preservando o comportamento já validado do aplicativo e reduzindo o risco de regressões durante a evolução para navegação e transporte BLE próprio.

Este plano não propõe reescrita, mudança de stack nem adoção de camadas cerimoniais. A arquitetura-base permanece válida: UI Compose, sessão persistente, backend com estado central, fronteiras separadas de provisionamento e transporte, MQTT somente de saída e adaptadores específicos no composition root.

## Princípios obrigatórios

1. Nenhuma etapa deste plano deve alterar deliberadamente o comportamento funcional já validado no T4A físico.
2. A UI não pode importar ou conhecer ThingClips/Tuya.
3. `T4ABackend` não pode instanciar implementações concretas de provisionamento ou transporte.
4. `T4AProvisioner` continua responsável por conta, inventário, descoberta, ativação e remoção de vínculo.
5. `T4ATransport` continua responsável exclusivamente pela sessão de runtime, conexão, DPS, cache e RSSI.
6. MQTT permanece exclusivamente de saída; não serão introduzidos comandos remotos para o T4A.
7. A localização existente continua sendo a única fonte GPS. Navegação futura deverá consumir `LocationSnapshot`.
8. A migração para Jetpack Compose é definitiva; não haverá retorno para XML salvo necessidade técnica comprovada.
9. Refatorações serão incrementais, pequenas e verificáveis.
10. Cada etapa deve manter o APK compilável e utilizável.

## Estado de partida

A arquitetura atual já possui:

- módulo `app` para UI, ciclo de vida Android, serviços e adaptadores Android;
- módulo `backend` para estado e regras centrais do T4A;
- `T4ASessionService` como proprietário de longa duração da sessão;
- `T4AProvisioner` e `T4ATransport` como portas independentes;
- `TuyaT4APlatform` como adaptador temporário;
- `T4AApplication` como composition root;
- `MqttTransport` separado de `PahoMqttTransport`;
- `LocationSnapshot` separado de `AndroidLocationProvider`;
- `T4ADashboardMapper` entre estado de domínio e estado de apresentação;
- `verifyTuyaBoundary` protegendo automaticamente a fronteira Tuya.

Os principais débitos atuais são:

- pouca cobertura de testes automatizados;
- `MainActivity.kt` com responsabilidades demais;
- `T4ADashboard.kt` grande e concentrando muitos componentes;
- dependência direta do núcleo em `SharedPreferences`, `Handler`, `Looper` e relógio do sistema;
- regras de bateria e estado cada vez mais importantes sem isolamento suficiente para testes unitários.

---

# Fase 0 — Baseline e proteção contra regressão

## Objetivo

Congelar o comportamento atual como referência antes de qualquer refatoração estrutural.

## Ações

- Registrar o commit de baseline usado para iniciar a execução deste plano.
- Confirmar build local e build CI do `master`.
- Confirmar instalação in-place sobre o APK existente.
- Validar fisicamente, no mínimo:
  - restauração de sessão;
  - conexão/reconexão BLE;
  - leitura de velocidade;
  - leitura de bateria;
  - farol;
  - partida;
  - cruzeiro;
  - modos de condução;
  - bloqueio/desbloqueio;
  - bloqueio automático;
  - publicação MQTT;
  - geolocalização.
- Preservar um log raw representativo do baseline.

## Critério de aceite

O baseline deve estar reproduzível e permitir comparação objetiva caso uma etapa posterior introduza regressão.

## Estado de execução

**Concluída no repositório.** O baseline formal está registrado em `docs/baseline-2026-08-30.md`. Instalação in-place, validação no T4A físico e log raw continuam como evidência de campo e não são simulados pelo CI.

---

# Fase 1 — Fundação de testes automatizados

## Objetivo

Criar uma rede mínima de segurança antes de aumentar a decomposição dos arquivos e do domínio.

## Prioridade 1 — testes puros e de baixo risco

Criar testes para:

### `T4ADashboardMapper`

Cobrir:

- velocidade;
- bateria presente/ausente;
- modo de condução;
- farol;
- partida;
- cruzeiro;
- lock;
- RSSI;
- odômetro total/percurso;
- valores desconhecidos ou DPS ausentes.

### `HomeAssistantDiscovery`

Cobrir:

- MAC normalizado;
- identificador estável do dispositivo;
- tópicos;
- payload mínimo;
- entidades esperadas;
- ausência de valores fabricados.

### `MqttConfiguration`

Cobrir:

- validação TCP;
- TLS;
- WS;
- WSS;
- host/porta/path;
- configurações inválidas.

## Prioridade 2 — regras de domínio

Criar doubles/fakes para `T4AProvisioner` e `T4ATransport` e testar:

- restauração de conta;
- ausência de conta;
- descoberta;
- pareamento;
- conexão;
- reconexão;
- publicação de comandos;
- confirmação por RX;
- pending DPS;
- semântica especial de lock;
- descarte do `INITIAL` falso de `blelock_switch`;
- auto-lock por RSSI.

## Prioridade 3 — bateria

Cobrir explicitamente as regras já observadas em campo:

- `INITIAL/cache` não estabelece SOC por conta própria;
- leitura RX abaixo de 100% passa a ter precedência;
- `RX=100` espúrio durante descarga não recupera artificialmente SOC;
- recuperação em repouso não deve ser confundida automaticamente com recarga;
- detecção de possível novo ciclo;
- confirmação manual do novo ciclo;
- rejeição manual do novo ciclo;
- persistência de mínimo/máximo observado.

### Nota de execução sobre `RX=100`

O tratamento específico de um `RX=100` potencialmente espúrio não será implementado como heurística nova nesta fase. O backend atual não possui uma fonte temporal injetável capaz de distinguir de forma determinística uma leitura espúria de uma leitura legítima após carga. Criar uma regra apenas pelo valor poderia rejeitar um `100%` real. A cobertura definitiva desse caso fica vinculada à introdução de `Clock`/`Scheduler` na Fase 4 e aos logs de campo.

## Critério de aceite

A suíte deve rodar automaticamente no build/CI e proteger as regras críticas antes das próximas fases.

## Estado de execução

**Concluída como fundação de testes.** A implementação e os resultados estão registrados em `docs/phase-1-test-foundation.md`. O CI executa os testes dos módulos `backend` e `app`, além de preservar a verificação da fronteira Tuya.

---

# Fase 2 — Decomposição da UI sem mudança visual

## Objetivo

Reduzir o tamanho físico dos arquivos Compose sem mudar aparência ou comportamento.

## 2.1 — `MainActivity.kt`

Manter em `MainActivity` somente responsabilidades de Activity:

- ciclo de vida;
- permissões;
- service binding;
- `setContent`;
- integração estritamente necessária com APIs de Activity.

Extrair progressivamente:

```text
ui/
  T4AApp.kt
  login/
    LoginScreen.kt
  pairing/
    PairingScreen.kt
  settings/
    SettingsScreen.kt
  diagnostics/
    DiagnosticsScreen.kt
  dashboard/
    ...
```

Nenhum fluxo funcional deve ser alterado durante essa etapa.

## 2.2 — Dashboard

Dividir `T4ADashboard.kt` em componentes coerentes, por exemplo:

```text
ui/dashboard/
  T4ADashboard.kt
  T4ADashboardState.kt
  T4ADashboardMapper.kt
  DashboardTokens.kt
  ConnectionCard.kt
  BatteryIndicator.kt
  SpeedGauge.kt
  MetricsCard.kt
  RidingControls.kt
  DashboardIcons.kt
```

A divisão final pode variar conforme dependências reais, mas cada arquivo deve possuir responsabilidade visual clara.

## 2.3 — Tokens visuais

Centralizar em `DashboardTokens`:

- dimensões;
- tipografia;
- espaçamentos;
- raios;
- cores semânticas;
- tamanhos de ícones.

Evitar duplicação de constantes visuais em `MainActivity` e componentes.

## Critério de aceite

A UI deve permanecer visual e funcionalmente equivalente ao baseline, com previews Compose úteis para os principais componentes.

---

# Fase 3 — Isolamento de persistência do núcleo

## Objetivo

Remover de `T4ABackend` a responsabilidade direta por `SharedPreferences` sem alterar os dados persistidos existentes.

## Ação principal

Introduzir uma porta, inicialmente pequena, por exemplo:

```text
T4AStateStore
```

Responsável apenas pelos dados atualmente persistidos pelo backend, como:

- estado conhecido do lock;
- configuração de auto-lock;
- distância de auto-lock;
- endereço BLE lembrado;
- observações de bateria;
- configuração da detecção de recarga.

Implementação Android:

```text
AndroidT4AStateStore
  -> SharedPreferences("t4a_backend")
```

## Regra de compatibilidade

Os mesmos nomes de preferences e chaves existentes devem ser preservados nesta fase para que atualizações in-place não percam estado.

## Critério de aceite

`T4ABackend` não recebe `Context` nem acessa `SharedPreferences` diretamente, e instalações existentes mantêm seus dados.

---

# Fase 4 — Isolamento de relógio e agendamento

## Objetivo

Permitir teste determinístico de timeouts, manutenção, reconexão e bateria.

## Portas mínimas

Introduzir abstrações simples, sem framework de DI:

```text
Clock
Scheduler
```

Exemplo conceitual:

```text
Clock.nowMillis()
Scheduler.post(...)
Scheduler.postDelayed(...)
Scheduler.cancel(...)
```

Implementações Android continuam usando:

- `System.currentTimeMillis()`;
- `Handler`;
- `Looper`.

## Aplicação inicial

Priorizar `T4ABackend`.

Depois avaliar `MqttTelemetryCoordinator` apenas se os testes mostrarem ganho real.

## Não fazer

Não criar framework próprio de concorrência, executor genérico ou arquitetura reativa complexa.

## Critério de aceite

Testes devem conseguir avançar artificialmente tempo para validar:

- lock confirmation timeout;
- RSSI timeout;
- reconnect foreground/background;
- manutenção periódica;
- gap mínimo de detecção de recarga;
- classificação segura de leituras suspeitas de bateria, incluindo `RX=100`, sem depender do relógio real.

---

# Fase 5 — Consolidação das fronteiras Tuya/BLE

## Objetivo

Preparar a implementação BLE própria sem ainda remover o SDK Tuya.

## Ações

- Revisar `T4ATransport` depois dos testes para confirmar que contém somente capacidades realmente necessárias ao runtime.
- Não adicionar métodos Tuya-específicos à interface.
- Se surgirem necessidades do protocolo BLE próprio, expressá-las em modelos neutros.
- Manter `T4AProvisioner` separado.
- Manter `TuyaT4APlatform` funcionando como implementação atual até a nova implementação estar validada fisicamente.
- Reforçar `verifyTuyaBoundary` sempre que novas áreas do domínio forem adicionadas.

## Resultado esperado

A futura troca deverá ser possível no composition root:

```text
T4AProvisioner -> implementação Tuya
T4ATransport   -> implementação BLE própria
```

sem alteração do dashboard ou das regras centrais do T4A.

## Critério de aceite

Uma fake implementation de `T4ATransport` deve conseguir executar os principais fluxos do backend em testes sem qualquer classe Tuya.

---

# Fase 6 — Preparação do domínio de navegação

## Objetivo

Criar a estrutura correta para o próximo marco sem misturar navegação com controle físico do patinete.

## Regra arquitetural

Navegação não pertence a `T4ABackend`.

Criar domínio paralelo, inicialmente neutro:

```text
backend/navigation/
  Route
  Waypoint
  RouteLeg
  NavigationInstruction
  NavigationState
  RouteParser
  NavigationEngine
```

O formato definitivo deve ser guiado pela importação real de rotas do Google Maps.

## Fluxo previsto

```text
URL compartilhada do Google Maps
        -> parser/importador
        -> Route
        -> NavigationEngine
        <- LocationSnapshot
        -> NavigationState
        -> UI Compose
```

## Regras

- reutilizar `LocationSnapshot`;
- não criar um segundo GPS;
- waypoint não implica necessariamente destino final;
- preservar ordem dos pontos;
- permitir persistência futura de percursos;
- não introduzir dependência Google Maps no núcleo neutro se não for necessária.

## Critério de aceite

O modelo deve conseguir representar uma rota compartilhada, seus pontos e uma instrução corrente sem depender do dashboard nem do SDK Tuya.

---

# Fase 7 — Persistência de rotas e percursos

## Objetivo

Introduzir persistência somente quando o modelo de navegação estiver estabilizado.

## Ações previstas

- definir identificador de rota;
- nome amigável;
- origem da importação;
- URL original, quando útil;
- waypoints ordenados;
- metadata necessária para retomada;
- última rota utilizada.

Avaliar Room somente nesta etapa e somente se a estrutura justificar banco relacional.

Não introduzir Room antecipadamente apenas para substituir preferences simples.

## Critério de aceite

Rotas salvas devem sobreviver a reinício do processo e atualização in-place do APK.

---

# Fase 8 — UI de navegação

## Objetivo

Adicionar orientação à tela principal sem comprometer legibilidade do painel do T4A.

## Entradas da UI

A UI deverá receber um `NavigationState`, não interpretar geometria ou URL do Google Maps diretamente.

Exemplos de estado:

- sem rota;
- rota carregada;
- aguardando início;
- seguir em frente;
- virar à esquerda;
- virar à direita;
- retorno;
- chegada a waypoint;
- chegada ao destino;
- fora da rota;
- posição insuficiente.

## Critério de aceite

A tela principal renderiza orientação a partir de estado pronto, sem acoplamento a parser, GPS Android ou Google Maps.

---

# Ordem recomendada de execução

A execução deve seguir esta ordem:

```text
0. Baseline
1. Testes
2. Decomposição da UI
3. Persistência neutra
4. Clock/Scheduler
5. Consolidação da fronteira BLE
6. Domínio de navegação
7. Persistência de rotas
8. UI de navegação
```

As fases 0 a 5 constituem o endurecimento arquitetural.

As fases 6 a 8 iniciam o próximo avanço funcional usando a base endurecida.

---

# Estratégia de branches e commits

Preferir branches curtas e temáticas.

Sugestão:

```text
feature/test-foundation
feature/ui-decomposition
feature/backend-state-store
feature/backend-scheduler
feature/transport-boundary
feature/navigation-domain
feature/route-persistence
feature/navigation-ui
```

Cada branch deve:

- possuir escopo único;
- manter build verde;
- evitar alteração funcional não relacionada;
- atualizar testes junto com alterações de regra;
- permitir comparação simples com o baseline;
- obedecer à classificação SemVer exigida pelo CI.

---

# Regras para evolução do plano

Este documento é vivo. Descobertas obtidas durante testes físicos, análise BLE ou implementação podem alterar fases futuras.

Mudanças no plano devem:

- registrar o motivo técnico;
- não apagar decisões anteriores sem justificativa;
- preservar os princípios obrigatórios;
- evitar transformar o projeto em uma arquitetura mais complexa do que o problema exige.
