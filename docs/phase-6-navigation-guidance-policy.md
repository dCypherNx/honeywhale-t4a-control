# Fase 6 — política de orientação

## Objetivo

Apresentar a instrução ativa escolhida pela rota sem inventar outra rota e sem antecipar uma conversão de forma ambígua.

OSRM continua sendo a autoridade sobre rota, ordem das manobras, branches escolhidos e metadados estruturados. `GuidanceEngine` não seleciona ruas nem recalcula progresso; ele somente decide se a instrução que já está ativa pode ser exposta com segurança.

## Modelo vigente

A política anterior baseada em 10 s para curvas, 12 s para rotatórias, 15 s para retorno, pisos de distância e fallback de 30 m foi descartada. Esses valores não são mais invariantes nem critérios de ativação.

Uma instrução direcional ativa fica elegível imediatamente, independentemente da velocidade, salvo quando os metadados OSRM mostram uma interseção anterior ainda não ultrapassada com uma saída fisicamente enterável no mesmo lado capaz de ser confundida com a conversão programada.

Nesse caso, a orientação direcional fica temporariamente oculta. Depois que a interseção ambígua é ultrapassada, a instrução OSRM original volta a ser elegível. O planejamento não muda.

Essa regra resolve apenas a segurança semântica da apresentação. A qualidade temporal percebida depende da progressão correta da rota, da conclusão correta da manobra e da observação física; não deve ser recriada aqui por outro horizonte fixo.

## Waypoints e apresentação como parada

Waypoint é um conceito estrutural da rota. Ele continua sendo respeitado por planejamento, progressão, persistência e recálculo independentemente de sua apresentação visual.

A preferência `waypointsVisible` controla somente a UX:

- habilitada: waypoint pode ser apresentado como parada e como parada alcançada;
- desabilitada: o waypoint continua obrigatório, mas `GuidanceEngine` faz lookahead para a próxima instrução visível e não o apresenta como parada.

A preferência não altera a rota nem a responsabilidade do OSRM.

## Ambiguidade de interseções

Para `TURN_LEFT` e `TURN_RIGHT`, o approach step preservado do OSRM é consultado. Uma interseção anterior bloqueia a indicação somente quando:

- ainda está à frente da posição projetada;
- não corresponde efetivamente à própria interseção alvo;
- OSRM marca uma saída alternativa como enterável;
- a saída alternativa fica no mesmo lado da instrução ativa;
- não é a saída escolhida pela rota atual.

A geometria compartilhada é calculada por `RouteGeometry`; `GuidanceEngine` não mantém uma implementação paralela de projeção.

Rotatórias e retornos não recebem regras topológicas inventadas enquanto não houver evidência/implementação suficientemente madura para isso.

## Separação de responsabilidades

`RouteProgressTracker` determina a interpretação física do fix sobre a rota. `NavigationEngine` determina qual instrução está ativa e quando ela foi cumprida. `GuidanceEngine` somente transforma esse estado em uma orientação apresentável. A UI consome o resultado e não contém regras de navegação.

Assim, uma futura melhoria no reconhecimento de uma curva ou na progressão não exige alterar componentes visuais.

## Validação

A retirada dos horizontes fixos elimina uma abordagem já rejeitada, mas não equivale a validação física. O comportamento esperado precisa ser testado em percurso real, especialmente em sequências de conversões próximas, interseções ambíguas, rotatórias, retornos e manobras concluídas sob diferentes qualidades de GPS.
