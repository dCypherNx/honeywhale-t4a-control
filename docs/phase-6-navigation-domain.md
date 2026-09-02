# Fase 6 — preparação do domínio de navegação

## Primeiro corte

Este corte cria o domínio neutro de navegação sem alterar o controle físico do T4A, a sessão BLE/Tuya, MQTT, persistência ou UI.

Foram introduzidos em `backend/navigation`:

- `Route` — rota imutável e independente do provedor;
- `Waypoint` — ponto ordenado com papel explícito de origem, passagem ou destino;
- `RouteLeg` — trecho entre dois waypoints;
- `NavigationInstruction` — instrução neutra de manobra;
- `NavigationState` — estado atual da navegação;
- `RouteParser` — contrato para futuros importadores de rotas externas;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya ou MQTT.
- O parser concreto de Google Maps não foi implementado neste corte. O formato definitivo será guiado por uma URL real compartilhada pelo Google Maps para evitar contrato especulativo.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino; um waypoint intermediário não é tratado como destino final.
- Persistência e UI permanecem para fases posteriores, conforme o plano de endurecimento arquitetural.

## Critério coberto

Os testes verificam que:

- a rota preserva a ordem e o papel dos waypoints;
- `NavigationEngine` recebe `LocationSnapshot` diretamente;
- uma instrução corrente pode ser produzida sem conhecimento do provedor;
- atingir uma instrução avança para a próxima;
- ausência de localização é um estado explícito.

## Próximo corte

Usar uma URL real de rota compartilhada do Google Maps, com pelo menos um ponto intermediário, para definir e implementar o primeiro `RouteParser` concreto sem vazar detalhes do Google para os modelos neutros.
