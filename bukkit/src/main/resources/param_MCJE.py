# Minecraft Java Edition 1.13+
# Connection and blockID : MCJE
# World parameters : MCJE

# from mcje.vec3 import Vec3
from mcpi.vec3 import Vec3

PLAYER_NAME = "PLAYER"  # player name in Minecraft
PLAYER_ORIGIN = Vec3(5200, 0, 5100)  # po.x, po.y, po.z
print(f"param_MCJE loaded for {PLAYER_NAME} at {PLAYER_ORIGIN.x}, {PLAYER_ORIGIN.y}, {PLAYER_ORIGIN.z}")

# minecraft remote connection to the host at address:port
ADRS_MCR = "localhost"
# PORT_MCR = 14712  # mod
PORT_MCR = 4711  # plugin server


# axis parameters
AXIS_WIDTH = 40  # x, z: -40 .. 0 .. 40
AXIS_TOP = 127
AXIS_Y_V_ORG = 96  # y of virtual origin
AXIS_BOTTOM = 63  # y: 63 .. 96 .. 127


# vertical levels
Y_TOP = 255  # the top where blocks can be set
Y_CLOUD_BOTTOM = 128  # the bottom of clouds
Y_SEA = 62  # the sea level
Y_BOTTOM = 0  # the bottom where blocks can be set
Y_BOTTOM_STEVE = -64  # the bottom where Steve can go down


# block IDs  You can find IDs here: https://minecraft-ids.grahamedgecombe.com/
AIR = "air"
STONE = "stone"
GRASS_BLOCK = "grass_block"
GOLD_BLOCK = "gold_block"
IRON_BLOCK = "iron_block"
GLOWSTONE = "glowstone"
SEA_LANTERN_BLOCK = "sea_lantern"

# some good blocks for grid like patterns you can count blocks easily
GLASS = "glass"
TNT = "tnt"
DIAMOND_BLOCK = "diamond_block"
FURNACE_INACTIVE = "furnace"
FURNACE_ACTIVE = "lit_furnace"
GLASS_PANE = "glass_pane"

RED_WOOL = "red_wool"
GREEN_WOOL = "green_wool"
BLUE_WOOL = "blue_wool"
YELLOW_WOOL = "yellow_wool"
ORANGE_WOOL = "orange_wool"
PURPLE_WOOL = "purple_wool"
WHITE_WOOL = "white_wool"
LIGHT_BLUE_WOOL = "light_blue_wool"
BLACK_WOOL = "black_wool"
BROWN_WOOL = "brown_wool"
CYAN_WOOL = "cyan_wool"
GRAY_WOOL = "gray_wool"
LIGHT_GRAY_WOOL = "light_gray_wool"
LIME_WOOL = "lime_wool"
MAGENTA_WOOL = "magenta_wool"
PINK_WOOL = "pink_wool"
