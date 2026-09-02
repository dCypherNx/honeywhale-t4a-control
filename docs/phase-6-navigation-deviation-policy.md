# Fase 6 — política de saída da rota

A saída da rota não é confirmada por uma única leitura de GPS. O fluxo é dividido em detecção geométrica, qualidade da posição e persistência temporal.

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

## 3. Debounce temporal

Para confirmar a saída da rota são necessárias simultaneamente:

- 3 leituras consecutivas válidas em `OFF_ROUTE`;
- pelo menos 6 segundos entre a primeira e a leitura que confirma o desvio.

Qualquer retorno à rota ou leitura de baixa qualidade zera a sequência.

Com o `AndroidLocationProvider` em movimento usando intervalo nominal de 3 s, o caso normal é:

`t=0 s -> primeira evidência`

`t=3 s -> segunda evidência`

`t=6 s -> terceira evidência e confirmação`

## 4. Recálculo

Após confirmação:

- `RouteRecalculator` usa a posição GPS atual como nova origem;
- waypoints já cumpridos são descartados;
- waypoints restantes e destino são preservados;
- o `RoutingProfile` importado do Google Maps é preservado;
- o planejamento OSRM roda fora da thread de UI;
- a rota ativa só é substituída quando o novo planejamento termina com sucesso.

Se o recálculo falhar, a rota anterior permanece ativa em `OFF_ROUTE`.

## 5. Cooldown

Depois de uma tentativa confirmada existe cooldown de 30 segundos antes que uma nova sequência possa disparar outro recálculo. Isso evita tempestade de requisições em GPS instável ou indisponibilidade temporária do serviço de rotas.

## Valores iniciais para teste físico

- corredor-base: 35 m;
- margem máxima de precisão: 25 m;
- precisão máxima aceita para evidência: 25 m;
- amostras consecutivas: 3;
- persistência mínima: 6 s;
- cooldown de recálculo: 30 s.

Esses valores são parâmetros iniciais deliberadamente conservadores e devem ser revisados apenas com evidência de logs reais de navegação no T4A.
