# Fase 2 — Decomposição da UI sem mudança visual

## Objetivo

Decompor os arquivos Compose concentrados sem alterar deliberadamente aparência, fluxos funcionais, semântica dos comandos ou fronteiras arquiteturais já validadas nas Fases 0 e 1.

## Baseline

A execução parte do `master` após a conclusão das Fases 0 e 1. A suíte automatizada criada na Fase 1 é utilizada como rede de proteção durante a decomposição.

## Alterações realizadas

### `MainActivity.kt`

Os Composables deixaram de residir na Activity. A Activity permanece responsável principalmente por:

- ciclo de vida Android;
- permissões Bluetooth/localização;
- binding com `T4ASessionService`;
- `setContent`;
- estado de integração necessário à composição;
- integração com `Window`, `MediaStore`, clipboard, `Intent`, dialogs e preferences;
- adaptação das ações da UI para a sessão atual.

Foram extraídos:

```text
ui/
  T4AApp.kt
  T4AIcon.kt
  T4AUiActions.kt
  T4AUiTheme.kt
  login/
    LoginScreen.kt
  pairing/
    PairingScreen.kt
  settings/
    SettingsScreen.kt
  diagnostics/
    DiagnosticsScreen.kt
```

### Dashboard

`T4ADashboard.kt` passou a ser o ponto de composição do dashboard, mantendo o preview principal. Seus elementos foram separados por responsabilidade visual:

```text
ui/dashboard/
  T4ADashboard.kt
  T4ADashboardState.kt
  T4ADashboardMapper.kt
  DashboardTokens.kt
  DashboardCard.kt
  DashboardIcons.kt
  ConnectionCard.kt
  SpeedGauge.kt
  MetricsCard.kt
  RidingControls.kt
```

`ConnectionCard.kt` também contém o indicador de bateria, por pertencer diretamente ao cartão de conexão no layout atual.

### Tokens visuais

Os tokens do dashboard foram movidos sem alteração de valores para `DashboardTokens.kt`.

Os tokens da moldura externa da aplicação e do tema foram centralizados em `T4AUiTheme.kt`, eliminando a duplicação de cores que existia em `MainActivity.kt`.

## Compatibilidade preservada

A refatoração preserva explicitamente:

- valores, dimensões, espaçamentos, raios, tipografia e cores existentes;
- sequência e condições dos fluxos de login e pareamento;
- persistência das seções expansíveis;
- tema system/light/dark;
- keep-screen-on;
- configuração de bateria;
- configuração MQTT;
- auto-lock e distância de auto-lock;
- unidade, cruise e modo de partida;
- diagnóstico de DPS;
- desvinculação Tuya;
- histórico de eventos;
- filtro, cópia e gravação do raw log;
- cabeçalho de versão do raw log;
- semântica especial do lock, incluindo a inversão existente ao publicar `DP_LOCK`;
- comportamento distinto de logging das alterações de auto-lock realizadas pela tela de Settings.

Nenhuma nova dependência de ThingClips/Tuya foi introduzida na UI.

## Previews

Foram mantido/adicionado previews Compose úteis para componentes principais, incluindo o dashboard e a tela de login.

## Validação automatizada

A decomposição foi executada em checkpoints. Após a decomposição do dashboard e novamente após a extração da `MainActivity`, o CI confirmou:

- validação da classe SemVer da branch;
- preservação da fronteira Tuya;
- compilação dos módulos envolvidos;
- execução da suíte de testes unitários de debug.

## Validação de campo pendente

A equivalência visual e a operação no T4A físico não podem ser comprovadas pelo CI. Antes de declarar a Fase 2 encerrada, o APK resultante deve ser instalado in-place e comparado com o baseline, verificando especialmente:

- aparência da tela principal em tema claro/escuro;
- login e pareamento, se aplicável;
- velocidade e bateria;
- modos de condução;
- farol, partida e cruise;
- lock/unlock e auto-lock;
- alternância do odômetro;
- Settings e persistência das seções;
- raw log, filtros, cópia e salvamento;
- MQTT e demais integrações já validadas no baseline.

Se a validação de campo não revelar regressão, a Fase 2 pode ser marcada como concluída sem alterações adicionais.
