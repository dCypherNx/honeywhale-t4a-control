# Fase 6 — preparação do domínio de navegação

## Primeiro corte

Este corte cria o domínio neutro de navegação sem alterar o controle físico do T4A, a sessão BLE/Tuya, MQTT, persistência ou UI.

Foram introduzidos em `backend/navigation`:

- `Route` — rota imutável e independente do provedor;
- `Waypoint` — ponto ordenado com papel explícito de origem, passagem ou destino;
- `RouteLeg` — trecho entre dois waypoints;
- `NavigationInstruction` — instrução neutra de manobra;
- `NavigationState` — estado atual da navegação;
- `RouteParser` — contrato para importadores de rotas externas;
- `RouteReferenceResolver` — fronteira neutra para resolver referências externas antes do parsing;
- `RoutePlanner` — fronteira neutra para enriquecer uma rota importada com geometria e instruções fornecidas por um motor externo;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya, MQTT ou de um motor específico de rotas.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino; um waypoint intermediário não é tratado como destino final.
- Um waypoint importado pode existir temporariamente sem coordenadas quando a URL fornece apenas endereço/label; latitude e longitude devem estar ambas presentes ou ambas ausentes.
- Importar uma rota e planejá-la são operações distintas: `RouteParser` recupera a intenção/ordem dos pontos; `RoutePlanner` resolve coordenadas faltantes e produz instruções.
- O projeto não dependerá de chave Google para navegação.
- Persistência e UI permanecem para cortes posteriores.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como short link:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

A URL expandida observada foi uma rota `/maps/dir/` contendo, em ordem:

1. origem em coordenadas `-23.6377584,-46.658869`;
2. waypoint intermediário em coordenadas `-23.6133508,-46.6884184`;
3. destino como endereço `Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil`.

Fluxo adotado:

```text
URL compartilhada maps.app.goo.gl
  -> RouteReferenceResolver
  -> URL expandida /maps/dir/...
  -> GoogleMapsRouteParser
  -> Route importada e neutra
  -> ValhallaRoutePlanner
       -> Nominatim somente para waypoint textual sem coordenadas
       -> Valhalla /route com costing motor_scooter
  -> Route com RouteLeg/NavigationInstruction
  -> NavigationEngine
  <- LocationSnapshot
  -> NavigationState
```

`GoogleMapsRouteReferenceResolver`, `GoogleMapsRouteParser` e `ValhallaRoutePlanner` ficam no módulo Android. Nenhuma dependência Google ou OSM entra no backend neutro.

O parser não interpreta o trecho `/@latitude,longitude,zoom` como waypoint; esse trecho representa viewport da URL. Também não depende dos parâmetros de tracking `utm_*`/`g_ep`.

## Planejamento sem chave

`ValhallaRoutePlanner` usa por padrão o servidor público FOSSGIS em `https://valhalla1.openstreetmap.de/route`, mas o endpoint permanece substituível. A requisição usa:

- `costing = motor_scooter`;
- `language = pt-BR`;
- unidades em quilômetros;
- `shape_format = polyline6`;
- origem e destino como `break`;
- waypoints intermediários como `through`, preservando a intenção de apenas orientar o percurso.

Quando um waypoint veio do Google Maps apenas como endereço textual, ele é resolvido pelo Nominatim público antes da chamada ao Valhalla. O adaptador envia `User-Agent` identificável e respeita o limite público de no máximo uma consulta Nominatim por segundo quando houver mais de um endereço sem coordenadas.

As manobras do Valhalla fornecem `begin_shape_index`; o adaptador decodifica o `polyline6` da perna e ancora cada `NavigationInstruction` na coordenada correspondente. Isso evita inventar coordenadas para curvas e prepara a base para distância até a próxima manobra.

## Critério coberto

Os testes verificam que:

- a rota preserva a ordem e o papel dos waypoints;
- `NavigationEngine` recebe `LocationSnapshot` diretamente;
- uma instrução corrente pode ser produzida sem conhecimento do provedor;
- atingir uma instrução avança para a próxima;
- ausência de localização é um estado explícito;
- referências já expandidas passam pelo resolver sem modificação;
- referência vazia é rejeitada antes de qualquer acesso de rede;
- a URL real fornecida na Fase 6 gera exatamente três waypoints na ordem origem -> passagem -> destino;
- origem e passagem preservam suas coordenadas;
- o destino textual permanece válido mesmo quando a URL não traz coordenadas explícitas;
- o request Valhalla usa `motor_scooter` e preserva os intermediários como `through`;
- manobras Valhalla são convertidas para a taxonomia neutra e ancoradas por `begin_shape_index`.

## Limites atuais

- O percurso resultante é recalculado pelo Valhalla usando os waypoints importados; ele não é uma reprodução binária do grafo interno do Google Maps.
- Servidores públicos Valhalla/Nominatim são adequados para desenvolvimento e uso leve, sem garantia de disponibilidade. Como os endpoints são substituíveis, uma instância própria pode ser adotada posteriormente sem alterar o domínio.
- Atribuição a OpenStreetMap/Valhalla deverá aparecer na UI quando a navegação for exposta ao usuário.
- Persistência de rotas, UI de navegação, detecção de saída da rota e recálculo permanecem fora deste corte.
