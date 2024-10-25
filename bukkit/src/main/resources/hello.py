# Hello World for Minecraft Java Edition Server
# mcje, MCJE: Minecraft Java Edition
import sys
# from mcje.minecraft import Minecraft
from mcpi.minecraft import Minecraft
import param_MCJE as param
from param_MCJE import PLAYER_ORIGIN as po


# Connect to minecraft and open a session as player with origin location
mc = Minecraft.create(address=param.ADRS_MCR, port=param.PORT_MCR)
msg = mc.setPlayer(param.PLAYER_NAME, po.x, po.y, po.z)
if ("Error" in msg):
    sys.exit(msg)
else:
    print(msg)

mc.postToChat("Hello, Minecraft Server!!")

# axis_flat.reset_minecraft_world(mc, width=48)
# axis_flat.clear_XYZ_axis(mc, wait=0)
# axis_flat.draw_XYZ_axis(mc, wait=0.3)

mc.setBlocks(0, 180, 0, 5, 200, 5, param.GOLD_BLOCK)
