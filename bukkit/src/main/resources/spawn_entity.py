import sys
from time import sleep

from mcje.minecraft import Minecraft
import param_MCJE as param
from param_MCJE import PLAYER_ORIGIN as po
from param_MCJE import block
from param_MCJE import entity
from param_MCJE import particle

# Connect to minecraft and open a session as player with origin location
mc = Minecraft.create(address=param.ADRS_MCR, port=param.PORT_MCR)
result = mc.setPlayer(param.PLAYER_NAME, po.x, po.y, po.z)
if "Error" in result:
    sys.exit(result)
else:
    print(result)

mc.postToChat("spawn entities")
mc.setBlocks(-59, 65, -59, -61, 65, -61, block.SEA_LANTERN)
for _i in range(8):
    # mc.spawnEntity(-60, 70, -60, entity.FOX)
    mc.spawnEntity(-60, 70, -60, entity.CAT)
    # mc.spawnEntity(-60, 70, -60, entity.BEE)
    mc.spawnParticle(-60, 70, -60, 1, 1, 1, particle.DRAGON_BREATH, 0.2, 10000)
    # print(mc.spawnEntity(70, 70, 70, entity.CREEPER))
