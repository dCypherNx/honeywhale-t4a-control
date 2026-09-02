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
- `RoutePlanner` — fronteira neutra para um futuro motor de roteamento;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya, MQTT ou de um motor específico de rotas.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino.
- Um waypoint importado pode existir temporariamente sem coordenadas quando a URL fornece apenas endereço/label.
- Importar uma rota e planejá-la são operações distintas.
- O projeto não dependerá de chave Google para navegação.
- Persistência e UI permanecem para cortes posteriores.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

A URL expandida observada contém, em ordem:

1. origem `-23.6377584,-46.658869`;
2. waypoint intermediário `-23.6133508,-46.6884184`;
3. destino textual `Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil`.

Fluxo preservado:

```text
Google Maps shared URL
  -> RouteReferenceResolver
  -> GoogleMapsRouteParser
  -> Route neutra com waypoints ordenados
  -> RoutePlanner (implementação ainda não escolhida)
  -> NavigationEngine
  <- LocationSnapshot
  -> NavigationState
```

## Valhalla — tentativa descartada

Foi implementado e avaliado um `RoutePlanner` baseado em Valhalla/OpenStreetMap usando o perfil `motor_scooter`, sem chave de API. O caso real fornecido pelo usuário não conseguiu ser roteado entre os pontos esperados na instância avaliada. Como o primeiro caso real já falhou, essa implementação foi removida do PR e não é considerada solução válida para o T4A.

Não será feita troca cega para outro serviço público apenas para obter sucesso técnico. O próximo motor deve atender ao caso real, não exigir credencial e permitir um perfil de circulação coerente com o T4A.

## Alternativas avaliadas

- Google Routes API: descartada porque exige chave/billing.
- GraphHopper hosted API: descartada porque exige chave.
- openrouteservice hosted API: descartada porque exige chave.
- OSRM público: sem chave, porém os perfis públicos são pré-processados e não oferecem um perfil próprio para o T4A.
- BRouter: aberto, executável localmente/Android e com perfis configuráveis. O perfil `moped` distribuído pelo projeto é explicitamente experimental e não deve ser usado diretamente para navegação real. Continua candidato apenas com perfil próprio e validação real.

## Próximo critério

O próximo experimento deve partir da mesma rota real e só será incorporado ao PR se conseguir roteá-la de forma plausível. A direção preferida é um motor OSM sob nosso controle, com perfil específico do T4A e sem dependência de credenciais externas.
