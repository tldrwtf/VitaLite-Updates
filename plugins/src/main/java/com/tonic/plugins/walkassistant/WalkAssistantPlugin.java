package com.tonic.plugins.walkassistant;

import com.google.inject.Provides;
import com.tonic.Logger;
import com.tonic.Static;
import com.tonic.data.locatables.BankLocations;
import com.tonic.services.pathfinder.Walker;
import com.tonic.util.ThreadPool;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.util.HotkeyListener;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

@PluginDescriptor(
    name = "# Walk Assistant",
    description = "Pathfinds you to quest/clue destination, walk to nearest bank, cancel walk.",
    tags = {"walker", "walk", "assistant", "hotkey", "bank", "quest", "clue"}
)
public class WalkAssistantPlugin extends Plugin
{
    @Inject
    private WalkAssistantConfig config;

    @Inject
    private KeyManager keyManager;

    @Inject
    private PluginManager pluginManager;

    private final AtomicReference<Future<?>> activeWalk = new AtomicReference<>(null);

    private void log(String message)
    {
        if (config.debugLogging())
        {
            Logger.info(message);
        }
    }

    private void logWarn(String message)
    {
        if (config.debugLogging())
        {
            Logger.warn(message);
        }
    }

    private final HotkeyListener questDestListener = new HotkeyListener(() -> config.questDestHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            onQuestDestHotkey();
        }
    };

    private final HotkeyListener cancelWalkListener = new HotkeyListener(() -> config.cancelWalkHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            onCancelWalkHotkey();
        }
    };

    private final HotkeyListener nearestBankListener = new HotkeyListener(() -> config.nearestBankHotkey())
    {
        @Override
        public void hotkeyPressed()
        {
            onNearestBankHotkey();
        }
    };

    @Provides
    WalkAssistantConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(WalkAssistantConfig.class);
    }

    @Override
    protected void startUp()
    {
        keyManager.registerKeyListener(questDestListener);
        keyManager.registerKeyListener(cancelWalkListener);
        keyManager.registerKeyListener(nearestBankListener);
    }

    @Override
    protected void shutDown()
    {
        keyManager.unregisterKeyListener(questDestListener);
        keyManager.unregisterKeyListener(cancelWalkListener);
        keyManager.unregisterKeyListener(nearestBankListener);
        cancelActiveWalk();
    }

    private void onNearestBankHotkey()
    {
        submitWalk(BankLocations::walkToNearest);
    }

    private void onCancelWalkHotkey()
    {
        if (!Walker.isWalking() && !isWalkActive())
        {
            log("[WalkAssistant] No active walk to cancel.");
            return;
        }
        cancelActiveWalk();
        log("[WalkAssistant] Walk cancelled.");
    }

    private void onQuestDestHotkey()
    {
        WorldPoint destination = resolveQuestOrClueDestination();
        if (destination == null)
        {
            logWarn("[WalkAssistant] No active Quest Helper step or Clue Scroll destination found.");
            return;
        }
        submitWalk(() -> {
            log("[WalkAssistant] Walking to quest/clue destination: " + destination);
            Walker.walkTo(destination);
        });
    }

    private boolean isWalkActive()
    {
        Future<?> f = activeWalk.get();
        return f != null && !f.isDone();
    }

    private void cancelActiveWalk()
    {
        Walker.cancel();
        Future<?> f = activeWalk.getAndSet(null);
        if (f != null)
        {
            f.cancel(true);
        }
    }

    private void submitWalk(Runnable walkTask)
    {
        if (Walker.isWalking() || isWalkActive())
        {
            logWarn("[WalkAssistant] Walk already in progress, ignoring hotkey.");
            return;
        }
        final Future<?>[] holder = new Future<?>[1];
        Future<?> future = ThreadPool.submit(() -> {
            try
            {
                walkTask.run();
            }
            finally
            {
                activeWalk.compareAndSet(holder[0], null);
            }
        });
        holder[0] = future;
        activeWalk.set(future);
    }

    private WorldPoint resolveQuestOrClueDestination()
    {
        WorldPoint qhPoint = tryQuestHelper();
        if (qhPoint != null)
        {
            return qhPoint;
        }
        return tryClueScroll();
    }

    private WorldPoint tryQuestHelper()
    {
        try
        {
            Collection<Plugin> plugins = pluginManager.getPlugins();
            Optional<Plugin> qhPlugin = plugins.stream()
                .filter(p -> p.getClass().getName().contains("QuestHelperPlugin"))
                .findFirst();

            if (!qhPlugin.isPresent())
            {
                logWarn("[WalkAssistant] QuestHelperPlugin not found in plugin list.");
                return null;
            }

            Plugin instance = qhPlugin.get();
            log("[WalkAssistant] Found QuestHelper plugin: " + instance.getClass().getName());

            Field questManagerField = findField(instance.getClass(), "questManager");
            if (questManagerField == null)
            {
                logWarn("[WalkAssistant] 'questManager' field not found on " + instance.getClass().getName());
                return null;
            }
            questManagerField.setAccessible(true);
            Object questManager = questManagerField.get(instance);
            if (questManager == null)
            {
                logWarn("[WalkAssistant] questManager field is null.");
                return null;
            }
            log("[WalkAssistant] questManager type: " + questManager.getClass().getName());

            Method getSelectedQuest = findMethod(questManager.getClass(), "getSelectedQuest");
            if (getSelectedQuest == null)
            {
                logWarn("[WalkAssistant] 'getSelectedQuest' method not found on " + questManager.getClass().getName());
                return null;
            }
            getSelectedQuest.setAccessible(true);
            Object selectedQuest = getSelectedQuest.invoke(questManager);
            if (selectedQuest == null)
            {
                logWarn("[WalkAssistant] getSelectedQuest() returned null - no quest currently active in Quest Helper.");
                return null;
            }
            log("[WalkAssistant] selectedQuest type: " + selectedQuest.getClass().getName());

            Method getCurrentStep = findMethod(selectedQuest.getClass(), "getCurrentStep");
            if (getCurrentStep == null)
            {
                logWarn("[WalkAssistant] 'getCurrentStep' method not found on " + selectedQuest.getClass().getName());
                return null;
            }
            getCurrentStep.setAccessible(true);
            Object currentStep = getCurrentStep.invoke(selectedQuest);
            if (currentStep == null)
            {
                logWarn("[WalkAssistant] getCurrentStep() returned null - no active step.");
                return null;
            }
            log("[WalkAssistant] currentStep type: " + currentStep.getClass().getName());

            Object activeStep = currentStep;
            Method getActiveStep = findMethod(currentStep.getClass(), "getActiveStep");
            if (getActiveStep != null)
            {
                getActiveStep.setAccessible(true);
                Object unwrapped = getActiveStep.invoke(currentStep);
                if (unwrapped != null)
                {
                    activeStep = unwrapped;
                    log("[WalkAssistant] Unwrapped to active step: " + activeStep.getClass().getName());
                }
            }

            WorldPoint destination = resolveMapPoint(activeStep);
            if (destination != null)
            {
                log("[WalkAssistant] Resolved destination from mapPoint: " + destination);
                return destination;
            }

            logWarn("[WalkAssistant] No mapPoint on step: " + activeStep.getClass().getName());
        }
        catch (Exception e)
        {
            logWarn("[WalkAssistant] Quest Helper reflection failed: " + e.getMessage());
        }
        return null;
    }

    private WorldPoint resolveMapPoint(Object step)
    {
        try
        {
            Field mapPointField = findField(step.getClass(), "mapPoint");
            if (mapPointField == null)
            {
                return null;
            }
            mapPointField.setAccessible(true);
            Object mapPoint = mapPointField.get(step);
            if (mapPoint == null)
            {
                return null;
            }
            Method getWp = findMethod(mapPoint.getClass(), "getWorldPoint");
            if (getWp != null)
            {
                getWp.setAccessible(true);
                Object wp = getWp.invoke(mapPoint);
                if (wp instanceof WorldPoint)
                {
                    return (WorldPoint) wp;
                }
            }
        }
        catch (Exception e)
        {
            logWarn("[WalkAssistant] mapPoint resolution failed: " + e.getMessage());
        }
        return null;
    }

    private WorldPoint tryClueScroll()
    {
        try
        {
            Collection<Plugin> plugins = pluginManager.getPlugins();
            Optional<Plugin> cluePlugin = plugins.stream()
                .filter(p -> p.getClass().getName().contains("ClueScrollPlugin"))
                .findFirst();

            if (!cluePlugin.isPresent())
            {
                return null;
            }

            Plugin instance = cluePlugin.get();
            Field clueField = findField(instance.getClass(), "clue");
            if (clueField == null)
            {
                return null;
            }
            clueField.setAccessible(true);
            Object clue = clueField.get(instance);
            if (clue == null)
            {
                return null;
            }

            Method getLocation = findMethod(clue.getClass(), "getLocation");
            if (getLocation != null)
            {
                getLocation.setAccessible(true);

                final Method locationMethod = getLocation;
                final Object clueInstance = clue;
                final Plugin pluginInstance = instance;
                Object result = Static.invoke(() -> {
                    try
                    {
                        if (locationMethod.getParameterCount() == 1)
                        {
                            return locationMethod.invoke(clueInstance, pluginInstance);
                        }
                        return locationMethod.invoke(clueInstance);
                    }
                    catch (Exception e)
                    {
                        return null;
                    }
                });

                if (result instanceof WorldPoint)
                {
                    log("[WalkAssistant] Resolved clue destination via getLocation(): " + result);
                    return (WorldPoint) result;
                }
            }

            Field locationField = findField(clue.getClass(), "location");
            if (locationField != null)
            {
                locationField.setAccessible(true);
                Object loc = locationField.get(clue);
                if (loc instanceof WorldPoint)
                {
                    log("[WalkAssistant] Resolved clue destination via 'location' field: " + loc);
                    return (WorldPoint) loc;
                }
            }

            Field npcLocationField = findField(clue.getClass(), "npcLocation");
            if (npcLocationField != null)
            {
                npcLocationField.setAccessible(true);
                Object npcLoc = npcLocationField.get(clue);
                if (npcLoc instanceof WorldPoint)
                {
                    log("[WalkAssistant] Resolved clue destination via 'npcLocation' field: " + npcLoc);
                    return (WorldPoint) npcLoc;
                }
            }

            logWarn("[WalkAssistant] No location could be resolved from clue type: " + clue.getClass().getName());
        }
        catch (Exception e)
        {
            logWarn("[WalkAssistant] ClueScroll reflection failed: " + e.getMessage());
        }
        return null;
    }

    private static Field findField(Class<?> clazz, String name)
    {
        while (clazz != null && clazz != Object.class)
        {
            try
            {
                return clazz.getDeclaredField(name);
            }
            catch (NoSuchFieldException ignored)
            {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name)
    {
        while (clazz != null && clazz != Object.class)
        {
            for (Method m : clazz.getDeclaredMethods())
            {
                if (m.getName().equals(name))
                {
                    return m;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }
}
