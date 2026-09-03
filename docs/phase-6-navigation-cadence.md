# Fase 6 — cadência adaptativa de localização e navegação

A navegação não usa mais um intervalo fixo de processamento. A frequência é definida pela urgência da próxima decisão e aplicada em dois níveis: requisição de localização ao Android e processamento do `NavigationEngine`.

## Princípios

- trechos longos e previsíveis podem relaxar até 20 s;
- a cadência é calculada pelo tempo restante até a zona crítica, considerando velocidade;
- a política consome no máximo metade da margem temporal restante antes da decisão se tornar crítica;
- uma interseção anterior com saída alternativa fisicamente possível passa a ser tratada como decisão mais próxima que a manobra programada;
- se os metadados do corredor forem insuficientes para provar que o trecho é simples, a cadência é limitada a 2 s;
- aproximação crítica, waypoint e suspeita/confirmacão de desvio usam solicitação de 500 ms;
- em condição crítica o motor não aplica throttle temporal e processa todo fix útil entregue pelo provedor;
- precisão alta é solicitada perto de decisões ou quando a geometria é incerta; trechos comprovadamente simples e distantes podem usar precisão balanceada;
- o `AndroidLocationProvider` reconfigura o `LocationRequest` quando a mudança de demanda é material, evitando re-registro a cada pequena variação;
- sem navegação ativa, permanecem as cadências da sessão: movimento 2 s/alta precisão e repouso 20 s/precisão balanceada;
- dispositivo desconectado continua desregistrando o provedor de localização.

## Exemplo

Uma conversão a 500 m não implica automaticamente 20 s. Esse intervalo só é permitido se velocidade e margem temporal permitirem e os metadados OSRM não indicarem uma saída alternativa mais próxima no trecho atual. Se houver uma rua lateral enterável antes da conversão, a política usa essa interseção como próxima decisão e eleva a frequência antes dela.

## Objetivo energético

A economia não se limita a reduzir cálculos. Quando a situação permite, o próprio `LocationRequest` é relaxado em intervalo, distância mínima e qualidade, reduzindo a necessidade de posicionamento de alta precisão. Quando a navegação exige reação rápida, a política faz o movimento inverso imediatamente.

Os limites atuais são parâmetros de validação física e devem ser recalibrados com logs reais, sem transformar valores iniciais em premissas permanentes.
