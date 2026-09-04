# Fase 6 — política viva de observação da navegação

## Objetivo

A navegação não é dirigida por uma tabela de intervalos nem por horizontes fixos de manobra. Ela mantém uma interpretação viva da posição sobre a rota e expressa quanto tempo a situação física atual ainda permite ficar sem nova observação antes de aumentar materialmente o risco de perder uma decisão.

OSRM permanece a autoridade de roteamento: fornece rota, geometria, steps, bearings, intersections e annotations. Referências a Mapbox, Valhalla, Waze ou outros projetos servem apenas como comparação de práticas de engenharia; nenhum deles substitui o OSRM na implementação.

## Uma interpretação geométrica compartilhada

`RouteProgressTracker` é o proprietário da continuidade da posição sobre a rota. Para cada fix ele produz um `RouteProgressSnapshot` contendo:

- leg atual;
- offset projetado sobre a geometria;
- erro lateral;
- consistência do progresso em relação à observação anterior;
- timestamp.

O mesmo snapshot é consumido por `NavigationEngine` e `NavigationObservationPolicy`. Assim, progresso e confiança deixam de ser recalculados de formas ligeiramente diferentes por componentes distintos.

Operações geométricas comuns ficam em `RouteGeometry`. Projeção, distância e utilidades de bearing não pertencem mais individualmente aos engines.

## Estado de observação

`NavigationObservationPolicy` combina o progresso compartilhado com a posição física, a precisão reportada, a velocidade e o contexto OSRM do trecho. Para cada fix ela deriva:

- confiança da localização;
- confiança do encaixe/progresso na rota;
- incerteza atual;
- erro lateral e progresso;
- próxima decisão física relevante;
- presença de saída alternativa enterável antes da instrução programada;
- clearance geométrico restante depois da incerteza;
- `observationBudget`, calculado continuamente a partir do clearance e do movimento atual.

Não existem nessa política horizontes de 10/12/15 segundos, teto de 20 segundos ou piso de 500 ms.

## Demanda ao provedor

O resultado contínuo é traduzido semanticamente para o adaptador Android:

- `CONTINUOUS`: desvio/recálculo em curso ou clearance esgotado;
- `PRECISE`: confiança ainda não está estável ou o corredor não pode ser conhecido com segurança;
- `BALANCED`: estado estável, mas há uma decisão física intermediária relevante à frente;
- `RELAXED`: posição e progresso estáveis em corredor conhecido e simples;
- `NONE`: navegação não exige localização.

Esses níveis não definem o momento da manobra. Eles apenas informam qualidade/urgência ao adaptador de aquisição.

Para um budget finito, `AndroidLocationProvider` usa esse próprio budget como hint de intervalo. Em `CONTINUOUS`, solicita a entrega mais rápida praticável pelo Android (`interval=0`, `minInterval=0`) em vez de inventar um piso de aplicação. Quando o domínio não encontra uma fronteira temporal finita, o adaptador recorre somente aos baselines já existentes da sessão: movimento 2 s/alta precisão e repouso 20 s/precisão balanceada. Dispositivo desconectado continua desregistrando o provedor.

Essa tradução do budget para `LocationRequest` é **experimental** e precisa de teste físico. O fato de a arquitetura permitir um budget contínuo não prova que consumir 100% dele como intervalo seja a melhor calibração; logs reais devem determinar se é necessário preservar uma margem dinâmica. Essa margem, caso exista, não deve voltar a ser um horizonte fixo de navegação.

## Processamento

`NavigationRuntime` não aplica throttle temporal próprio. Todo fix entregue pelo Android é disponibilizado ao progresso, engine e política de observação. Economia de energia é buscada na aquisição física quando o contexto permite, e não descartando informação depois que o GPS já produziu o fix.

## Responsabilidades

A divisão atual é:

- `RoutePlanner`: contrato provider-neutral de planejamento;
- `OsrmRoutePlanner`: adaptador OSRM no módulo Android;
- `RouteGeometry`: operações geométricas compartilhadas;
- `RouteProgressTracker`: continuidade e projeção física da rota;
- `NavigationEngine`: avanço lógico entre legs/instruções, conclusão e estado geométrico de rota;
- `GuidanceEngine`: apresentação segura da instrução ativa;
- `NavigationObservationPolicy`: confiança, risco e necessidade de observação;
- `NavigationDeviationPolicy`: confirmação temporal de saída da rota;
- `NavigationRecoveryCoordinator`: orquestra pré-aquecimento e recálculo em background;
- `NavigationRuntime`: lifecycle process-local, integração dos componentes e publicação de estado/listeners.

`NavigationRuntime` não implementa mais diretamente o workflow de recuperação.

## Diagnóstico

Cada fix observado registra `[NAV] OBS` com confiança, incerteza, lateral, progresso, distância da decisão, clearance, budget, conhecimento do corredor, existência de branch, consistência e demanda. `[NAV] LOCATION_DEMAND` registra mudanças da demanda efetivamente enviada ao adaptador.

Esses dados existem para que a próxima calibração seja baseada em percurso físico, não em novos números escolhidos antecipadamente.

## Fronteiras do projeto

Esta política não altera telemetria Tuya, MQTT, controle BLE, `T4AState` ou layout do dashboard. O mesmo `AndroidLocationProvider` continua fornecendo a posição já utilizada pela sessão/telemetria; navegação apenas publica sua demanda para esse adaptador e recebe o mesmo `LocationSnapshot`.
