# Fase 6 — política de saída da rota

A saída da rota não é confirmada por uma única leitura de GPS. O fluxo é dividido em detecção geométrica, qualidade da posição, pré-aquecimento seletivo de recuperação e persistência temporal.

## 1. Detecção geométrica

`NavigationEngine` projeta a posição atual sobre a geometria do trecho ativo.

O corredor permitido é calculado como:

`35 m + margem de precisão do GPS`

A margem de precisão é limitada a 25 m. Assim:

- precisão de 5 m -> OFF_ROUTE acima de aproximadamente 40 m da geometria;
- precisão de 10 m -> acima de aproximadamente 45 m;
- precisão de 25 m -> acima de aproximadamente 60 m;
- precisão desconhecida ou pior que 25 m usa 25 m apenas para a detecção visual, mas não pode confirmar recálculo.

## 2. Qualidade mínima para recálculo

`NavigationDeviationPolicy` só aceita evidência quando:

- `accuracyMeters > 0`;
- `accuracyMeters <= 25 m`;
- timestamp da posição é válido;
- o estado produzido pelo engine é `OFF_ROUTE`.

Uma posição sem precisão conhecida (`accuracyMeters == 0`) ou com precisão pior que 25 m zera a sequência de evidências.

## 3. Pré-aquecimento seletivo de recuperação

`NavigationRecoveryPolicy` permite iniciar uma rota de recuperação em background antes da confirmação final do desvio, mas apenas em um caso deliberadamente restrito:

- primeira evidência `OFF_ROUTE` com GPS de boa qualidade;
- a instrução que estava ativa é direcional (`TURN_LEFT`, `TURN_RIGHT`, `U_TURN` ou `ROUNDABOUT`).

Isso cobre o caso mais relevante de uma conversão perdida ou conversão errada sem calcular rotas alternativas continuamente em todas as interseções. A rota principal e a UI não são alteradas por uma predição.

O pré-aquecimento usa a primeira posição real já observada fora da rota como origem. Isso é preferível a inventar pontos hipotéticos em ruas laterais: o cliente não possui o grafo viário do OSRM e o serviço remoto continua sendo a autoridade para encaixe na malha e cálculo da rota.

Se as leituras seguintes voltarem para a rota, a predição é descartada. Se o desvio for confirmado, a rota pré-calculada pode ser promovida imediatamente quando ainda pertencer:

- à mesma rota original;
- ao mesmo leg;
- a uma origem com no máximo 20 segundos de idade;
- a uma origem a até 250 m da posição atual.

Se a predição estiver em andamento quando ocorre a confirmação, o runtime aguarda essa requisição já iniciada em vez de disparar imediatamente uma segunda chamada idêntica. Se ela falhar ou ficar inválida, o recálculo normal parte da posição confirmada.

## 4. Debounce temporal

Para confirmar a saída da rota são necessárias simultaneamente:

- 3 leituras consecutivas válidas em `OFF_ROUTE`;
- pelo menos 6 segundos entre a primeira e a leitura que confirma o desvio.

Qualquer retorno à rota ou leitura de baixa qualidade zera a sequência. O processamento de navegação usa cadência nominal de 2 s; portanto, o requisito temporal de 6 s continua sendo a barreira determinante mesmo que três amostras sejam obtidas antes disso.

## 5. Recálculo confirmado

Após confirmação:

- uma rota de recuperação pré-aquecida válida é promovida sem nova espera de rede;
- caso contrário, `RouteRecalculator` usa a posição GPS atual como nova origem;
- waypoints já cumpridos são descartados;
- waypoints restantes e destino são preservados;
- o `RoutingProfile` importado do Google Maps é preservado;
- o planejamento OSRM roda fora da thread de UI;
- a rota ativa só é substituída quando o novo planejamento termina com sucesso.

A UI só entra em `RECALCULATING` depois da confirmação normal. Pré-aquecimento nunca é apresentado ao usuário como desvio confirmado.

Se o recálculo confirmado falhar, a última orientação útil anterior é restaurada e a rota original permanece ativa.

## 6. Cooldown

Depois de uma tentativa confirmada existe cooldown de 30 segundos antes que uma nova sequência possa disparar outro recálculo confirmado. Isso evita tempestade de requisições em GPS instável ou indisponibilidade temporária do serviço de rotas.

O pré-aquecimento é limitado a uma única requisição por episódio de desvio direcional e é descartado assim que o veículo retorna à rota, a sessão é pausada/encerrada ou a rota ativa muda.

## Valores iniciais para teste físico

- corredor-base: 35 m;
- margem máxima de precisão: 25 m;
- precisão máxima aceita para evidência: 25 m;
- amostras consecutivas: 3;
- persistência mínima: 6 s;
- cooldown de recálculo confirmado: 30 s;
- idade máxima de uma recuperação pré-aquecida: 20 s;
- distância máxima entre origem pré-aquecida e posição de confirmação: 250 m.

Esses valores são parâmetros iniciais conservadores. Devem ser ajustados a partir de logs de navegação e comportamento físico observado, não tratados como premissas definitivas.
