# P2P Minecraft Project - Server side
## Description
Create a P2P Minecraft system using Purpur API (for the server side) and FabricMC (for the client side). FastAPI is used for the backend service.

## Technical details
### Server side (Purpur plugin logic)
At a fixed rate, all the server world data is sent to a central server (preferably hosted on a free cloud service, e.g. Fly.io).

Only delta patches are sent to the server to minimize data transfer workload (XDelta is used for this purpose).

Only one host can be active at a time. When there is no active host, the user can become the server and the other users
can connect to it.

### Client side (FabricMC mod logic)
Go to the following repo: https://github.com/lauralex/p2pmcclient

### P2P backend service (FastAPI server logic)
Go to the following repo: https://github.com/lauralex/p2pmctracker
