package me.ellbristow.mychunk;

import java.io.File;
import java.io.IOException;
import java.util.*;
import me.ellbristow.mychunk.lang.Lang;
import me.ellbristow.mychunk.listeners.*;
import me.ellbristow.mychunk.utils.Metrics;
import me.ellbristow.mychunk.utils.SQLiteBridge;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

public class MyChunk extends JavaPlugin {

    private FileConfiguration config;
    private static boolean foundEconomy = false;
    
    // Toggleable settings
    private static boolean unclaimRefund = false;
    private static double refundPercent = 100.00;
    private static boolean allowNeighbours = false;
    private static boolean allowOverbuy = false;
    private static boolean allowMobGrief = true;
    private static boolean protectUnclaimed = false;
    private static boolean unclaimedTNT = false;
    private static boolean useClaimExpiry = false;
    private static boolean allowNether = true;
    private static boolean allowEnd = true;
    private static boolean preventEntry = false;
    private static boolean preventPVP = false;
    private static int claimExpiryDays;
    private static boolean overbuyP2P = true;
    private static double chunkPrice = 0.00;
    private static double overbuyPrice = 0.00;
    private static boolean firstChunkFree = false;
    private static boolean rampChunkPrice = false;
    private static double priceRampRate = 25.00;
    private static int maxChunks = 8;
    private static boolean notify = true;
    private static Set<String> worlds = new HashSet<String>();
    private MyChunkVaultLink vault;
    
    // LOOK! SQLite stuff!
    private String[] tableColumns = {"world","x","z","owner","allowed","salePrice","allowMobs","allowPVP","lastActive", "PRIMARY KEY"};
    private String[] tableDims = {"TEXT NOT NULL", "INTEGER NOT NULL", "INTEGER NOT NULL", "TEXT NOT NULL", "TEXT NOT NULL", "INTEGER NOT NULL", "INTEGER(1) NOT NULL", "INTEGER(1) NOT NULL", "LONG NOT NULL", "(world, x, z)"};
    
    @Override
    public void onEnable() {
        
        // init Config
        loadConfig(false);
        
        // init SQLite
        initSQLite();
        
        // Register Events
        getServer().getPluginManager().registerEvents(new AmbientListener(), this);
        getServer().getPluginManager().registerEvents(new BlockListener(), this);
        getServer().getPluginManager().registerEvents(new MobListener(), this);
        getServer().getPluginManager().registerEvents(new PlayerListener(), this);
        getServer().getPluginManager().registerEvents(new SignListener(), this);
        if (FactionsHook.foundFactions()) {
            getServer().getPluginManager().registerEvents(new FactionsHook(), this);
            getLogger().info("Hooked into [Factions]");
        }
        
        try {
            Metrics metrics = new Metrics(this);
            metrics.start();
        } catch (IOException e) {
            // Failed to submit the stats :-(
        }
        
    }
    
    @Override
    public void onDisable() {
        SQLiteBridge.close();
    }
    
    @Override
    public boolean onCommand (CommandSender sender, Command cmd, String commandLabel, String[] args) {
        if (args.length == 0) {
            if (sender.hasPermission("mychunk.commands.stats")) {
                PluginDescriptionFile pdfFile = getDescription();
                sender.sendMessage(ChatColor.GOLD + "MyChunk v"  + ChatColor.WHITE + pdfFile.getVersion() + ChatColor.GOLD + " "+Lang.get("By")+" " + ChatColor.WHITE + "ellbristow");
                HashMap<Integer, HashMap<String, Object>> result = SQLiteBridge.select("COUNT(*) AS counter", "MyChunks", "", "", "");
                Object count = result.get(0).get("counter");
                if (sender instanceof Player) {
                    sender.sendMessage(ChatColor.GOLD + Lang.get("ChunksOwned")+": " + ChatColor.WHITE + ownedChunkCount(sender.getName()) +"  "+ ChatColor.GOLD + Lang.get("TotalClaimedChunks")+": " + ChatColor.WHITE + count);
                } else {
                    sender.sendMessage(ChatColor.GOLD + Lang.get("TotalClaimedChunks")+": " + ChatColor.WHITE + count);
                }
                String yourMax;
                int playerMax = getMaxChunks(sender);
                if (playerMax != 0) {
                    yourMax = ChatColor.GRAY + " ("+Lang.get("Yours")+": " + playerMax + ")";
                } else {
                    yourMax = ChatColor.GRAY + " ("+Lang.get("Yours")+": "+Lang.get("Unlimited")+")";
                }
                sender.sendMessage(ChatColor.GOLD + Lang.get("DefaultMax")+": " + ChatColor.WHITE + maxChunks + yourMax);
                if (foundEconomy){
                    double thisPrice = chunkPrice;
                    String rampMessage = ChatColor.GOLD + Lang.get("PriceRamping")+": " + ChatColor.WHITE + (rampChunkPrice && priceRampRate != 0 ? "Yes" : "No");
                    if (rampChunkPrice && priceRampRate != 0) {
                        rampMessage += " " + ChatColor.GOLD + Lang.get("RampRate") + ": " + ChatColor.WHITE + priceRampRate;
                        if (sender instanceof Player) {
                            int claimed = MyChunkChunk.getOwnedChunkCount(sender.getName());
                            if (firstChunkFree && claimed > 0) {
                                claimed--;
                            }
                            thisPrice += priceRampRate * claimed;
                        }
                    }
                    sender.sendMessage(rampMessage);
                    sender.sendMessage(ChatColor.GOLD + Lang.get("ChunkPrice")+": " + ChatColor.WHITE + MyChunkVaultLink.getEconomy().format(thisPrice) + " " + ChatColor.GOLD + Lang.get("UnclaimRefunds")+": " + ChatColor.WHITE + (unclaimRefund && refundPercent != 0 ? refundPercent + "%" : "No"));
                    String overFee = "";
                    if (allowOverbuy) {
                        String resales = "exc.";
                        if (overbuyP2P) {
                            resales = "inc.";
                        }
                        overFee = " " + ChatColor.GOLD + Lang.get("OverbuyFee")+": " + ChatColor.WHITE + MyChunkVaultLink.getEconomy().format(overbuyPrice) + "(" + resales +" "+Lang.get("Resales")+")";
                    }
                    sender.sendMessage(ChatColor.GOLD + Lang.get("AllowOverbuy")+": " + ChatColor.WHITE + Lang.get(""+allowOverbuy) + overFee);
                }
                sender.sendMessage(ChatColor.GOLD + Lang.get("AllowMobGrief") + ": " + ChatColor.WHITE + (allowMobGrief?Lang.get("Yes"):Lang.get("No")));
                sender.sendMessage(ChatColor.GOLD + Lang.get("OwnerNotifications") + ": " + ChatColor.WHITE + (notify?Lang.get("Yes"):Lang.get("No"))+ " " + ChatColor.GOLD + Lang.get("PreventEntry") + ": " + ChatColor.WHITE + (preventEntry?Lang.get("Yes"):Lang.get("No")));
                sender.sendMessage(ChatColor.GOLD + Lang.get("AllowNeighbours")+": " + ChatColor.WHITE + Lang.get(""+allowNeighbours) + ChatColor.GOLD + "  "+Lang.get("ProtectUnclaimed")+": " + ChatColor.WHITE + Lang.get(""+protectUnclaimed));
                sender.sendMessage(ChatColor.GOLD + Lang.get("PreventEntry")+": " + ChatColor.WHITE + (preventEntry?Lang.get("Yes"):Lang.get("No")) + ChatColor.GOLD + "  " + Lang.get("PreventPVP")+": " + ChatColor.WHITE + (preventPVP?Lang.get("Yes"):Lang.get("No")));
                String claimExpiry;
                if (!useClaimExpiry) {
                    claimExpiry = Lang.get("Disabled");
                } else {
                    claimExpiry = claimExpiryDays + " "+Lang.get("DaysWithoutLogin");
                }
                sender.sendMessage(ChatColor.GOLD + Lang.get("ClaimExpiry")+": " + ChatColor.WHITE + claimExpiry);
                sender.sendMessage(ChatColor.GOLD + Lang.get("AllowNether")+": " + ChatColor.WHITE + Lang.get(""+allowNether) + "  " + ChatColor.GOLD + Lang.get("AllowEnd")+": " + ChatColor.WHITE + Lang.get(""+allowEnd));
                return true;
            } else {
                sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
            }
        } else if (args.length == 1) {
            if (args[0].equalsIgnoreCase("flags")) {
                if (sender.hasPermission("mychunk.commands.flags")) {
                    sender.sendMessage(ChatColor.GOLD + "MyChunk "+Lang.get("PermissionFlags"));
                    sender.sendMessage(ChatColor.GREEN + "*" + ChatColor.GOLD + " = "+Lang.get("All")+" | " + ChatColor.GREEN + "B" + ChatColor.GOLD + " = "+Lang.get("Build")+" | " + ChatColor.GREEN + "C" + ChatColor.GOLD + " = "+Lang.get("AccessChests")+" | " + ChatColor.GREEN + "D"  + ChatColor.GOLD + " = "+Lang.get("Destroy"));
                    sender.sendMessage(ChatColor.GREEN + "E" + ChatColor.GOLD + Lang.get("Enter") + " | " + ChatColor.GREEN + "I"  + ChatColor.GOLD + " = "+Lang.get("IgniteBlocks")+" | " + ChatColor.GREEN + "L"  + ChatColor.GOLD + " = "+Lang.get("DropLava") );
                    sender.sendMessage(ChatColor.GREEN + "O"  + ChatColor.GOLD + " = "+Lang.get("OpenWoodenDoors") + " | " + ChatColor.GREEN + "U"  + ChatColor.GOLD + " = "+Lang.get("UseButtonsLevers")+" | " + ChatColor.GREEN + "W"  + ChatColor.GOLD + " = "+Lang.get("DropWater"));
                    return true;
                } else {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
            } else if (args[0].equalsIgnoreCase("max")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyNewMaxChunks"));
                sender.sendMessage(ChatColor.RED + "/mychunk max {"+Lang.get("NewLimit")+"}");
                return false;
            } else if (args[0].equalsIgnoreCase("price")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyNewChunkPrice"));
                sender.sendMessage(ChatColor.RED + "/mychunk price {"+Lang.get("NewPrice")+"}");
                return false;
            } else if (args[0].equalsIgnoreCase("obprice")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyNewOverbuyPrice"));
                sender.sendMessage(ChatColor.RED + "/mychunk obprice {"+Lang.get("NewPrice")+"}");
                return false;
            } else if (args[0].equalsIgnoreCase("toggle")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyToggle"));
                sender.sendMessage(ChatColor.RED + "/mychunk toggle {refund | overbuy | neighbours | resales | unclaimed | expiry | allownether | allowend | notify | firstChunkFree | preventEntry | preventPVP | mobGrief }");
                return false;
            } else if (args[0].equalsIgnoreCase("purgep")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyPurgePlayer"));
                sender.sendMessage(ChatColor.RED + "/mychunk purgep ["+Lang.get("PlayerName")+"]");
                return false;
            } else if (args[0].equalsIgnoreCase("purgew")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyPurgeWorld"));
                sender.sendMessage(ChatColor.RED + "/mychunk purgew ["+Lang.get("WorldName")+"]");
                return false;
            } else if (args[0].equalsIgnoreCase("refund")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyRefund"));
                sender.sendMessage(ChatColor.RED + "/mychunk refund ["+Lang.get("Percentage")+"]");
                return false;
            } else if (args[0].equalsIgnoreCase("rampRate")) {
                sender.sendMessage(ChatColor.RED + Lang.get("SpecifyRampRate"));
                sender.sendMessage(ChatColor.RED + "/mychunk rampRate ["+Lang.get("NewRampRate")+"]");
                return false;
            } else if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("mychunk.commands.reload")) {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return true;
                }
                reloadConfig();
                loadConfig(true);
                Lang.reload();
                sender.sendMessage(ChatColor.GOLD + Lang.get("Reloaded"));
            } else if (args[0].equalsIgnoreCase("info")) {
                if (!sender.hasPermission("mychunk.commands.info")) {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
                if (!(sender instanceof Player)) {
                    sender.sendMessage(ChatColor.RED + Lang.get("ServerNoChunks"));
                    return true;
                }
                List<MyChunkChunk> chunks = MyChunkChunk.getOwnedChunks(sender.getName());
                if (chunks.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "You do not own any chunks!");
                } else {
                    sender.sendMessage(ChatColor.GOLD + "Your owned chunks: (" + chunks.size() + ")");
                    for (int i = 0; i < chunks.size(); i++) {
                        Chunk thisChunk = Bukkit.getWorld(chunks.get(i).getWorldName()).getChunkAt(chunks.get(i).getX(), chunks.get(i).getZ());
                        sender.sendMessage(ChatColor.GOLD + " World: " + ChatColor.GRAY + thisChunk.getWorld().getName() + ChatColor.GOLD + " X: " + ChatColor.GRAY + thisChunk.getBlock(7, 0, 7).getX() + ChatColor.GOLD + " Z: " + ChatColor.GRAY + thisChunk.getBlock(7, 0, 7).getZ());
                    }
                }
            }
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("price")) {
                if (sender.hasPermission("mychunk.commands.price")) {
                    if (!foundEconomy) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoEcoPlugin"));
                        return false;
                    } else {
                        double newPrice;
                        try {
                            newPrice = Double.parseDouble(args[1]);
                        } catch (NumberFormatException e) {
                            // TODO: Lang
                            sender.sendMessage(ChatColor.RED + "Amount must be a number! (e.g. 5.00)");
                            sender.sendMessage(ChatColor.RED + "/mychunk price {new_price}");
                            return false;
                        }
                        config.set("chunk_price", newPrice);
                        chunkPrice = newPrice;
                        saveConfig();
                        sender.sendMessage(ChatColor.GOLD + "Chunk price set to " + MyChunkVaultLink.getEconomy().format(newPrice));
                        return true;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
            } else if (args[0].equalsIgnoreCase("obprice")) {
                if (sender.hasPermission("mychunk.commands.obprice")) {
                    if (!foundEconomy) {
                        sender.sendMessage(ChatColor.RED + "There is no economy plugin running! Command aborted.");
                        return false;
                    } else {
                        double newPrice;
                        try {
                            newPrice = Double.parseDouble(args[1]);
                        } catch (NumberFormatException e) {
                            sender.sendMessage(ChatColor.RED + "Amount must be a number! (e.g. 5.00)");
                            sender.sendMessage(ChatColor.RED + "/mychunk obprice {new_price}");
                            return false;
                        }
                        config.set("overbuy_price", newPrice);
                        overbuyPrice = newPrice;
                        saveConfig();
                        sender.sendMessage(ChatColor.GOLD + "Overbuy price set to " + MyChunkVaultLink.getEconomy().format(newPrice));
                        return true;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
            } else if (args[0].equalsIgnoreCase("rampRate")) {
                if (sender.hasPermission("mychunk.commands.ramprate")) {
                    if (!foundEconomy) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoEcoPlugin"));
                        return false;
                    } else {
                        double newRate;
                        try {
                            newRate = Double.parseDouble(args[1]);
                        } catch (NumberFormatException e) {
                            // TODO: Lang
                            sender.sendMessage(ChatColor.RED + "Rate must be a number! (e.g. 5.00)");
                            sender.sendMessage(ChatColor.RED + "/mychunk rampRate {New rate}");
                            return false;
                        }
                        config.set("pricae_ramp_rate", newRate);
                        priceRampRate = newRate;
                        saveConfig();
                        sender.sendMessage(ChatColor.GOLD + "Chunk ramp rate set to " + newRate);
                        return true;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
            } else if (args[0].equalsIgnoreCase("toggle")) {
                if (args[1].equalsIgnoreCase("refund")) {
                    if (sender.hasPermission("mychunk.commands.toggle.refund")) {
                        if (!foundEconomy) {
                            sender.sendMessage(ChatColor.RED + "There is no economy plugin running! Command aborted.");
                            return false;
                        } else {
                            if (unclaimRefund) {
                                unclaimRefund = false;
                                sender.sendMessage(ChatColor.GOLD + "Unclaiming chunks now " + ChatColor.RED + "DOES NOT" + ChatColor.GOLD + " provide a refund.");
                            } else {
                                unclaimRefund = true;
                                sender.sendMessage(ChatColor.GOLD + "Unclaiming chunks now " + ChatColor.GREEN + "DOES" + ChatColor.GOLD + " provides a refund.");
                            }
                            config.set("unclaim_refund", unclaimRefund);
                            saveConfig();
                            return true;
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("overbuy")) {
                    if (sender.hasPermission("mychunk.commands.toggle.overbuy")) {
                        if (!foundEconomy) {
                            sender.sendMessage(ChatColor.RED + "There is no economy plugin running! Command aborted.");
                            return false;
                        } else {
                            if (allowOverbuy) {
                                allowOverbuy = false;
                                sender.sendMessage(ChatColor.GOLD + "Buying over the chunk limit is now "+ ChatColor.RED + "disabled");
                            } else {
                                allowOverbuy = true;
                                sender.sendMessage(ChatColor.GOLD + "Buying over the chunk limit is now "+ ChatColor.GREEN + "enabled");
                            }
                            config.set("allow_overbuy", allowOverbuy);
                            saveConfig();
                            return true;
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("resales")) {
                    if (sender.hasPermission("mychunk.commands.toggle.resales")) {
                        if (!foundEconomy) {
                            sender.sendMessage(ChatColor.RED + "There is no economy plugin running! Command aborted.");
                            return false;
                        }
                        if (!allowOverbuy) {
                            sender.sendMessage(ChatColor.RED + "Overbuying is disabled! Command aborted.");
                            return false;
                        }
                        if (overbuyP2P) {
                            overbuyP2P = false;
                            sender.sendMessage(ChatColor.GOLD + "Overbuy fee when buying from other players is now "+ ChatColor.RED + "disabled");
                        } else {
                            overbuyP2P = true;
                            sender.sendMessage(ChatColor.GOLD + "Overbuy fee when buying from other players is now "+ ChatColor.GREEN + "enabled");
                        }
                        config.set("charge_overbuy_on_resales", overbuyP2P);
                        saveConfig();
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("neighbours")) {
                    if (sender.hasPermission("mychunk.commands.toggle.neighbours")) {
                        if (allowNeighbours) {
                            allowNeighbours = false;
                            sender.sendMessage(ChatColor.GOLD + "Claiming chunks next to other players "+ ChatColor.RED + "disabled");
                        } else {
                            allowNeighbours = true;
                            sender.sendMessage(ChatColor.GOLD + "Claiming chunks next to other players "+ ChatColor.GREEN + "enabled");
                        }
                        config.set("allow_neighbours", allowNeighbours);
                        saveConfig();
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("unclaimed")) {
                    if (sender.hasPermission("mychunk.commands.toggle.unclaimed")) {
                        if (protectUnclaimed) {
                            protectUnclaimed = false;
                            sender.sendMessage(ChatColor.GOLD + "Unclaimed chunks are now "+ChatColor.RED+"NOT"+ChatColor.GOLD+" protected");
                        } else {
                            protectUnclaimed = true;
                            sender.sendMessage(ChatColor.GOLD + "Unclaimed chunks are now "+ChatColor.GREEN+"protected");
                        }
                        config.set("protect_unclaimed", protectUnclaimed);
                        saveConfig();
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("tnt")) {
                    if (sender.hasPermission("mychunk.commands.toggle.tnt")) {
                        if (protectUnclaimed) {
                            if (unclaimedTNT) {
                                unclaimedTNT = false;
                                sender.sendMessage(ChatColor.GOLD + "Unclaimed chunks are now "+ChatColor.RED+"NOT"+ChatColor.GOLD+" protected from TNT");
                            } else {
                                unclaimedTNT = true;
                                sender.sendMessage(ChatColor.GOLD + "Unclaimed chunks are now "+ChatColor.GREEN+"protected from TNT");
                            }
                            config.set("prevent_tnt_in_unclaimed", unclaimedTNT);
                            saveConfig();
                            return true;
                        } else {
                            sender.sendMessage(ChatColor.RED + "Protect Unclaimed must be enabled to toggle TNT!");
                            return true;
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("expiry")) {
                    if (sender.hasPermission("mychunk.commands.toggle.expiry")) {
                        if (useClaimExpiry) {
                            useClaimExpiry = false;
                            sender.sendMessage(ChatColor.GOLD + "Claimed chunks will now "+ChatColor.RED+"NOT"+ChatColor.GOLD+" expire after inactivity");
                        } else {
                            useClaimExpiry = true;
                            sender.sendMessage(ChatColor.GOLD + "Claimed chunks "+ChatColor.GREEN+"WILL"+ChatColor.GOLD+" now expire after " + claimExpiryDays + " days of inactivity");
                            renewAllOwnerships();
                            sender.sendMessage(ChatColor.GOLD + "All activity records have been reset to this time");
                        }
                        config.set("useClaimExpiry", useClaimExpiry);
                        saveConfig();
                        return true;
                    } else {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                } else if (args[1].equalsIgnoreCase("allownether")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.allownether")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (allowNether) {
                        allowNether = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleNetherCannot"));
                    } else {
                        allowNether = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleNetherCan"));
                    }
                    config.set("allowNether", allowNether);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("allowend")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.allowend")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (allowEnd) {
                        allowEnd = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleEndCannot"));
                    } else {
                        allowEnd = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleEndCan"));
                    }
                    config.set("allowEnd", allowEnd);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("notify")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.notify")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (notify) {
                        notify = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleNotifyOff"));
                    } else {
                        notify = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleNotifyOn"));
                    }
                    config.set("owner_notifications", notify);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("firstChunkFree")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.firstchunkfree")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (firstChunkFree) {
                        firstChunkFree = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleFirstChunkFreeOff"));
                    } else {
                        firstChunkFree = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleFirstChunkFreeOn"));
                    }
                    config.set("first_chunk_free", firstChunkFree);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("rampChunkPrice")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.rampchunkprice")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (rampChunkPrice) {
                        rampChunkPrice = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleRampChunkPriceOff"));
                    } else {
                        rampChunkPrice = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleRampChunkPriceOn"));
                    }
                    config.set("ramp_chunk_price", rampChunkPrice);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("preventEntry")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.preventEntry")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (preventEntry) {
                        preventEntry = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleEntryOff"));
                    } else {
                        preventEntry = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleEntryOn"));
                    }
                    config.set("prevent_chunk_entry", preventEntry);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("preventPVP")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.preventPVP")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (preventPVP) {
                        preventPVP = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("TogglePVPOff"));
                    } else {
                        preventPVP = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("TogglePVPOn"));
                    }
                    config.set("preventPVP", preventPVP);
                    saveConfig();
                    return true;
                } else if (args[1].equalsIgnoreCase("mobGrief")) {
                    if (!sender.hasPermission("mychunk.commands.toggle.mobGrief")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (allowMobGrief) {
                        allowMobGrief = false;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleMobGriefOff"));
                    } else {
                        allowMobGrief = true;
                        sender.sendMessage(ChatColor.GOLD + Lang.get("ToggleMobGriefOn"));
                    }
                    config.set("allow_mob_greifing", allowMobGrief);
                    saveConfig();
                    return true;
                }
            } else if (args[0].equalsIgnoreCase("max")) {
                if (sender.hasPermission("mychunk.commands.max")) {
                    int newMax;
                    try {
                        newMax = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                    sender.sendMessage(ChatColor.RED + "Amount must be an integer! (0 = unlimited)");
                    sender.sendMessage(ChatColor.RED + "/mychunk max {new_max}");
                    return false;
                    }
                    
                    config.set("max_chunks", newMax);
                    maxChunks = newMax;
                    sender.sendMessage(ChatColor.GOLD + "Max Chunks is now set at " + ChatColor.WHITE + newMax + ChatColor.GOLD + "!");
                    saveConfig();
                    return true;
                } else {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
            } else if (args[0].equalsIgnoreCase("expiryDays")) {
                if (sender.hasPermission("mychunk.commands.expirydays")) {
                    if (!useClaimExpiry) {
                        sender.sendMessage(ChatColor.RED + "Claim expiry is disabled!");
                        return true;
                    }
                    int newDays;
                    try {
                        newDays = Integer.parseInt(args[1]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(ChatColor.RED + "Amount must be an integer!");
                        sender.sendMessage(ChatColor.RED + "/mychunk expirydays {new_days}");
                        return false;
                    }
                    if (newDays <= 0) {
                        sender.sendMessage(ChatColor.RED + "Amount must be greater than 0!");
                        sender.sendMessage(ChatColor.RED + "/mychunk expirydays {new_days}");
                        return false;
                    }
                    config.set("claimExpiresAfter", newDays);
                    claimExpiryDays = newDays;
                    sender.sendMessage(ChatColor.GOLD + "Claimed chunks will now expire after " + ChatColor.GREEN + claimExpiryDays + ChatColor.GOLD + " days of inactivity");
                    saveConfig();
                    return true;
                } else {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
            } else if (args[0].equalsIgnoreCase("purgep")) {
                if (!sender.hasPermission("mychunk.commands.purgep")) {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
                OfflinePlayer player = getServer().getOfflinePlayer(args[1]);
                if (!player.hasPlayedBefore() && !player.isOnline()) {
                    sender.sendMessage(ChatColor.RED + "Player "+ChatColor.WHITE+args[1]+ChatColor.RED+" not found!");
                    return false;
                }
                HashMap<Integer, HashMap<String, Object>> results = SQLiteBridge.select("world, x, z","MyChunks","owner = '"+player.getName()+"'","","");
                List<Chunk> chunks = new ArrayList<Chunk>();
                for (int i = 0; i < results.size(); i++) {
                    HashMap<String, Object> result = results.get(i);
                    String world = (String)result.get("world");
                    int x = Integer.parseInt(result.get("x")+"");
                    int z = Integer.parseInt(result.get("z")+"");
                    chunks.add(getServer().getWorld(world).getChunkAt(x, z));
                }
                for (Chunk thisChunk: chunks) {
                    MyChunkChunk.unclaim(thisChunk);
                }
                sender.sendMessage(ChatColor.GOLD + "All chunks for " + ChatColor.WHITE + player.getName() + ChatColor.GOLD + " are now Unowned!");
            } else if (args[0].equalsIgnoreCase("purgew")) {
                if (!sender.hasPermission("mychunk.commands.purgew")) {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
                World world = getServer().getWorld(args[1]);
                if (world == null) {
                    sender.sendMessage(ChatColor.RED + "World "+ChatColor.WHITE+args[1]+ChatColor.RED+" not found!");
                    return false;
                }
                String worldName = world.getName();
                SQLiteBridge.query("DELETE FROM MyChunks WHERE world = '"+worldName+"'");
                sender.sendMessage(ChatColor.GOLD + "All chunks in " + ChatColor.WHITE + worldName + ChatColor.GOLD + " are now Unowned!");
            } else if (args[0].equalsIgnoreCase("refund")) {
                if (!sender.hasPermission("mychunk.commands.refund")) {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
                double percent;
                try {
                    percent = Double.parseDouble(args[1]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + Lang.get("RefundNotNumber"));
                    return false;
                }
                refundPercent = percent;
                config.set("refund_percent", refundPercent);
                saveConfig();
                sender.sendMessage(ChatColor.GOLD + Lang.get("RefundSet") + ChatColor.WHITE + refundPercent + "%");
            } else if (args[0].equalsIgnoreCase("world")) {
                if (args[1].equalsIgnoreCase("enable")) {
                    if (!sender.hasPermission("mychunk.commands.world.enable")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + Lang.get("CommandMustBeRunByPlayer"));
                        return false;
                    }
                    Player player = (Player) sender;
                    String worldName = player.getWorld().getName();
                    worlds.add(worldName);
                    config.set("worlds", new ArrayList<String>(worlds));
                    saveConfig();
                    sender.sendMessage(ChatColor.GOLD + Lang.get("WorldEnabled") + ": " + worldName);
                } else if (args[1].equalsIgnoreCase("disable")) {
                    if (!sender.hasPermission("mychunk.commands.world.enable")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (!(sender instanceof Player)) {
                        sender.sendMessage(ChatColor.RED + Lang.get("CommandMustBeRunByPlayer"));
                        return false;
                    }
                    Player player = (Player) sender;
                    String worldName = player.getWorld().getName();
                    worlds.remove(worldName);
                    config.set("worlds", new ArrayList<String>(worlds));
                    saveConfig();
                    sender.sendMessage(ChatColor.GOLD + Lang.get("WorldDisabled") + ": " + worldName);
                }
            }  else if (args[0].equalsIgnoreCase("info")) {
                if (!sender.hasPermission("mychunk.commands.info.others")) {
                    sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                    return false;
                }
                OfflinePlayer player = getServer().getOfflinePlayer(args[1]);
                if (!player.hasPlayedBefore() && !player.isOnline()) {
                    sender.sendMessage(ChatColor.RED + "Player "+ChatColor.WHITE+args[1]+ChatColor.RED+" not found!");
                    return false;
                }
                List<MyChunkChunk> chunks = MyChunkChunk.getOwnedChunks(player.getName());
                if (chunks.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + player.getName() + " does not own any chunks!");
                } else {
                    sender.sendMessage(ChatColor.GOLD + player.getName() + "'s owned chunks: (" + chunks.size() + ")");
                    for (int i = 0; i < chunks.size(); i++) {
                        Chunk thisChunk = Bukkit.getWorld(chunks.get(i).getWorldName()).getChunkAt(chunks.get(i).getX(), chunks.get(i).getZ());
                        sender.sendMessage(ChatColor.GOLD + " World: " + ChatColor.GRAY + thisChunk.getWorld().getName() + ChatColor.GOLD + " X: " + ChatColor.GRAY + thisChunk.getBlock(7, 0, 7).getX() + ChatColor.GOLD + " Z: " + ChatColor.GRAY + thisChunk.getBlock(7, 0, 7).getZ());
            }
        } else if (args.length == 3) {
            if (args[0].equalsIgnoreCase("world")) {
                if (args[1].equalsIgnoreCase("enable")) {
                    if (!sender.hasPermission("mychunk.commands.world.enable")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (args[2].equalsIgnoreCase("all")) {
                        if (!sender.hasPermission("mychunk.commands.world.enable.all")) {
                            sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                            return false;
                        }
                        for (World world : getServer().getWorlds()) {
                            worlds.add(world.getName());
                        }
                    } else {
                        worlds.add(args[2]);
                    }
                    config.set("worlds", new ArrayList<String>(worlds));
                    saveConfig();
                    if (args[2].equalsIgnoreCase("all")) {
                        sender.sendMessage(ChatColor.GOLD + Lang.get("AllWorldsEnabled"));
                    } else {
                        sender.sendMessage(ChatColor.GOLD + Lang.get("WorldEnabled") + ": " + args[2]);
                    }
                } else if (args[1].equalsIgnoreCase("disable")) {
                    if (!sender.hasPermission("mychunk.commands.world.disable")) {
                        sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                        return false;
                    }
                    if (args[2].equalsIgnoreCase("all")) {
                        if (!sender.hasPermission("mychunk.commands.world.disable.all")) {
                            sender.sendMessage(ChatColor.RED + Lang.get("NoPermsCommand"));
                            return false;
                        }
                        worlds.clear();
                    } else {
                        worlds.remove(args[2]);
                    }
                    config.set("worlds", new ArrayList<String>(worlds));
                    saveConfig();
                    if (args[2].equalsIgnoreCase("all")) {
                        sender.sendMessage(ChatColor.GOLD + Lang.get("AllWorldsDisabled"));
                    } else {
                        sender.sendMessage(ChatColor.GOLD + Lang.get("WorldDisabled") + ": " + args[2]);
                }
            }
        }
        return false;
    }
    
    private void renewAllOwnerships() {
        SQLiteBridge.query("UPDATE MyChunks SET lastActive = " + (new Date().getTime() / 1000));
    }
    
    /**
     * Get the number of chunks a player already owns
     * 
     * @param playerName Player to check
     * @return Number of chunks player owns
     */
    public int ownedChunkCount(String playerName) {
        HashMap<Integer, HashMap<String, Object>> results = SQLiteBridge.select("COUNT(*) as counter", "MyChunks", "owner = '"+playerName+"'", "","");
        return Integer.parseInt(results.get(0).get("counter")+"");
    }
    
    /**
     * Get the maximum chunk allowance for a player
     * 
     * @param player Player to check
     * @return Number of chunks player can claim
     */
    public static int getMaxChunks(CommandSender player) {
        int max = maxChunks;
        if (player instanceof Player) {
            if (player.hasPermission("mychunk.claim.max.0") || player.hasPermission("mychunk.claim.unlimited")) {
                max = 0;
            } else {
                for (int i = 1; i <= 256; i++) {
                    if (player.hasPermission("mychunk.claim.max." + i)) {
                        max = i;
                    }
                }
            }
        } else {
            max = 0;
        }
        return max;
    }
    
    private void initSQLite() {
        if (!SQLiteBridge.checkTable("MyChunks")) {
            // Create empty table
            SQLiteBridge.createTable("MyChunks", tableColumns, tableDims);
        }
        // Check Missing Columns
        if (!SQLiteBridge.tableContainsColumn("MyChunks", "allowPVP")) {
            SQLiteBridge.addColumn("MyChunks", "allowPVP INT(1) NOT NULL DEFAULT 0");
        }
        File chunkFile = new File(getDataFolder(),"chunks.yml");
        if (chunkFile.exists()) {
            // Transfer old data
            getLogger().info("Converting old YML data to SQLite");
            FileConfiguration chunkStore = YamlConfiguration.loadConfiguration(chunkFile);
            Set<String> keys = chunkStore.getKeys(false);
            String values = "";
            for (String key : keys) {
                if (!key.equalsIgnoreCase("TotalOwned")) {
                    int split = key.lastIndexOf("_", key.lastIndexOf("_") - 1);
                    String world = key.substring(0, split);
                    String[] elements = key.substring(split + 1).split("_");
                    int x = Integer.parseInt(elements[0]);
                    int z = Integer.parseInt(elements[1]);
                    String owner = chunkStore.getString(key+".owner");
                    String allowed = chunkStore.getString(key+".allowed","");
                    double salePrice = chunkStore.getDouble(key+".forsale",0);
                    boolean allowMobs = chunkStore.getBoolean(key+".allowmobs",false);
                    long lastActive = chunkStore.getLong(key+".lastActive",0);
                    if (!values.equals("")) {
                        values += ",";
                    }
                    SQLiteBridge.query("INSERT OR REPLACE INTO MyChunks (world,x,z,owner,allowed,salePrice,allowMobs,allowPVP,lastActive) VALUES ('"+world+"',"+x+","+z+",'"+owner+"','"+allowed+"',"+salePrice+","+(allowMobs?"1":"0")+",0,"+lastActive+")");
                }
            }
            getLogger().info("YML > SQLite Conversion Complete!");
        }
        chunkFile.delete();
    }
    
    private void loadConfig(boolean reload) {
        if (reload) {
            reloadConfig();
        }
        config = getConfig();
        maxChunks = config.getInt("max_chunks", 8);
        config.set("max_chunks", maxChunks);
        allowNeighbours = config.getBoolean("allow_neighbours", false);
        config.set("allow_neighbours", allowNeighbours);
        protectUnclaimed = config.getBoolean("protect_unclaimed", false);
        config.set("protect_unclaimed", protectUnclaimed);
        unclaimedTNT = config.getBoolean("prevent_tnt_in_unclaimed", true);
        config.set("prevent_tnt_in_unclaimed", unclaimedTNT);
        useClaimExpiry = config.getBoolean("useClaimExpiry", false);
        config.set("useClaimExpiry", useClaimExpiry);
        claimExpiryDays = config.getInt("claimExpiresAfter", 7);
        config.set("claimExpiresAfter", claimExpiryDays);
        allowNether = config.getBoolean("allowNether", true);
        config.set("allowNether", allowNether);
        allowEnd = config.getBoolean("allowEnd", true);
        config.set("allowEnd", allowEnd);
        notify = config.getBoolean("owner_notifications", true);
        config.set("owner_notifications", notify);
        refundPercent = config.getDouble("refund_percent", 100);
        config.set("refund_percent", refundPercent);
        List<String> worldsList = config.getStringList("worlds");
        config.set("worlds", worldsList);
        worlds = new HashSet<String>(worldsList);
        allowMobGrief = config.getBoolean("allow_mob_griefing", true);
        config.set("allow_mob_griefing", allowMobGrief);
        preventEntry = config.getBoolean("prevent_chunk_entry", false);
        config.set("prevent_chunk_entry", preventEntry);
        preventPVP = config.getBoolean("preventPVP", false);
        config.set("preventPVP", preventPVP);
        if (getServer().getPluginManager().isPluginEnabled("Vault")) {
            vault = new MyChunkVaultLink(this);
            getLogger().info("[Vault] found and hooked!");
            if (vault.foundEconomy) {
                foundEconomy = true;
                String message = "[" + vault.economyName + "] found and hooked!";
                getLogger().info(message);
                chunkPrice = config.getDouble("chunk_price", 0.00);
                config.set("chunk_price", chunkPrice);
                unclaimRefund = config.getBoolean("unclaim_refund", false);
                config.set("unclaim_refund", unclaimRefund);
                allowOverbuy = config.getBoolean("allow_overbuy", false);
                config.set("allow_overbuy", allowOverbuy);
                overbuyPrice = config.getDouble("overbuy_price", 0.00);
                config.set("overbuy_price", overbuyPrice);
                overbuyP2P = config.getBoolean("charge_overbuy_on_resales", true);
                config.set("charge_overbuy_on_resales", overbuyP2P);
                firstChunkFree = config.getBoolean("first_chunk_free", false);
                config.set("first_chunk_free", firstChunkFree);
                rampChunkPrice = config.getBoolean("ramp_chunk_price", false);
                config.set("ramp_chunk_price", rampChunkPrice);
                priceRampRate = config.getDouble("price_ramp_rate", 25.00);
                config.set("price_ramp_rate", priceRampRate);
            } else if (!reload) {
                getLogger().info("No economy plugin found! Chunks will be free");
            }
        } else if (!reload) {
            getLogger().info("Vault not found! Chunks will be free");
        }
        saveConfig();
    }
    
    /**
     * Return current value of double settings
     * <p>
     * Checkable settings: (not case sensitive)<br>
     * claimExpiryDays - Returns the number of days before a chunk claim expires<br>
     * maxChunks - Returns the maximum number of chunks a player can own
     * 
     * @param setting
     * @return Setting value or 0 if not found
     */
    public static int getIntSetting(String setting) {
        if (setting.equalsIgnoreCase("claimExpiryDays")) return claimExpiryDays;
        if (setting.equalsIgnoreCase("max_chunks")) return maxChunks;
        return 0;
    }
    
    /**
     * Return current value of double settings
     * <p>
     * Checkable settings: (not case sensitive)<br>
     * chunkPrice - Default price of a chunk<br>
     * priceRampRate - Default price of a chunk<br>
     * overbuyPrice - Premium added to chunk price when overbuying
     * overbuyPrice - Percentage refunded when unclaiming chunks
     * 
     * @param setting
     * @return Setting value or 0 if not found
     */
    public static double getDoubleSetting(String setting) {
        if (setting.equalsIgnoreCase("chunkPrice")) return chunkPrice;
        if (setting.equalsIgnoreCase("priceRampRate")) return priceRampRate;
        if (setting.equalsIgnoreCase("overbuyPrice")) return overbuyPrice;
        if (setting.equalsIgnoreCase("refundPercent")) return refundPercent;
        return 0;
    }
    
    /**
     * Return current state of boolean settings
     * <p>
     * Checkable settings: (not case sensitive)<br>
     * allowEnd - Checks if players can claim chunks in End worlds<br>
     * allowNeighbours - Checks if players can claim chunks next to other players<br>
     * allowNether - Checks if players can claim chunks in Nether worlds<br>
     * allowOverbuy - Checks if players can buy more chunks than their allowance<br>
     * foundEconomy - Checks if an economy plugin was found<br>
     * protectUnclaimed - Checks if unclaimed chunks are protected<br>
     * unclaimRefund - Checks if players receive a refund hen unclaiming chunks<br>
     * unclaimedTNT - Checks if TNT is blocked when protectUnclimed is on<br>
     * useClaimExpiry - Checks if chunk ownership expires<br>
     * ownerNotifications - Checks if owners receive notifications<br>
     * firstChunkFree - Checks if players can claim 1 chunk for free
     * rampChunkPrice - Checks if chunk prices increase with each claim
     * 
     * @param setting
     * @return Setting state or false if not found
     */
    public static boolean getToggle(String setting) {
        if (setting.equalsIgnoreCase("allowEnd")) return allowEnd;
        if (setting.equalsIgnoreCase("allowNeighbours")) return allowNeighbours;
        if (setting.equalsIgnoreCase("allowNether")) return allowNether;
        if (setting.equalsIgnoreCase("allowOverbuy")) return allowOverbuy;
        if (setting.equalsIgnoreCase("foundEconomy")) return foundEconomy;
        if (setting.equalsIgnoreCase("overbuyP2P")) return overbuyP2P;
        if (setting.equalsIgnoreCase("protectUnclaimed")) return protectUnclaimed;
        if (setting.equalsIgnoreCase("unclaimRefund")) return unclaimRefund;
        if (setting.equalsIgnoreCase("unclaimedTNT")) return unclaimedTNT;
        if (setting.equalsIgnoreCase("useClaimExpiry")) return useClaimExpiry;
        if (setting.equalsIgnoreCase("ownerNotifications")) return notify;
        if (setting.equalsIgnoreCase("firstChunkFree")) return firstChunkFree;
        if (setting.equalsIgnoreCase("preventEntry")) return preventEntry;
        if (setting.equalsIgnoreCase("preventPVP")) return preventPVP;
        if (setting.equalsIgnoreCase("allowMobGrief")) return allowMobGrief;
        if (setting.equalsIgnoreCase("rampChunkPrice")) return rampChunkPrice;
        return false;
    }
    
    public static boolean isWorldEnabled(String name) {
        return worlds.contains(name);
    }
}
