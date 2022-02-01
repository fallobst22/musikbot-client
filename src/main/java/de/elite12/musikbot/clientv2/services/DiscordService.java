package de.elite12.musikbot.clientv2.services;

import de.elite12.musikbot.clientv2.core.Clientv2ServiceProperties;
import de.elite12.musikbot.clientv2.events.SongFinishedEvent;
import de.elite12.musikbot.clientv2.events.StartSongEvent;
import de.elite12.musikbot.clientv2.events.StopSongEvent;
import de.elite12.musikbot.clientv2.util.AudioSource;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.audio.AudioSendHandler;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.interaction.SlashCommandEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.managers.AudioManager;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import javax.security.auth.login.LoginException;
import javax.sound.sampled.LineUnavailableException;
import java.util.EnumSet;
import java.util.Objects;

import static net.dv8tion.jda.api.requests.GatewayIntent.GUILD_VOICE_STATES;

@Service
public class DiscordService extends ListenerAdapter {

    private final Logger logger = LoggerFactory.getLogger(DiscordService.class);
    private final JDA JDA;

    public DiscordService(Clientv2ServiceProperties properties) throws LoginException, InterruptedException {
        JDABuilder builder = JDABuilder.create(properties.getDiscordToken(), EnumSet.of(GUILD_VOICE_STATES));

        //Retrieve events in this service
        builder.addEventListeners(this);
        builder.setActivity(null);

        this.JDA = builder.build();

        this.JDA.updateCommands().addCommands(
                new CommandData("join", "Instruct the Bot to join your current voice channel"),
                new CommandData("leave", "Instruct the Bot to leave the current voice channel")
        ).queue();

        this.JDA.awaitReady();
    }

    @Override
    public void onSlashCommand(@NotNull SlashCommandEvent event) {
        if (event.getName().equals("join")) {
            this.onJoinCommand(event);
        }
        if (event.getName().equals("leave")) {
            this.onLeaveCommand(event);
        }
    }

    private void onLeaveCommand(@NotNull SlashCommandEvent event) {
        //Defer Reply
        event.deferReply().queue();
        //Get InteractionHook to later reply
        InteractionHook interactionHook = event.getHook();
        //The Member who caused the Event
        Member eventMember = event.getMember();

        //First condition is sufficient for eventMember to not be null due to the API contract, but for better IDE integration we check explicitly for null too
        if (!event.isFromGuild() || eventMember == null) {
            interactionHook.editOriginal("This can only be used from inside a Guild!").queue();
            return;
        }

        Guild guild = Objects.requireNonNull(event.getGuild());
        AudioManager audioManager = guild.getAudioManager();

        if (!audioManager.isConnected()) {
            interactionHook.editOriginal("I am not currently in a voice channel!").queue();
            return;
        }

        audioManager.closeAudioConnection();

        AudioSendHandler sendingHandler = audioManager.getSendingHandler();

        if (sendingHandler instanceof AudioSource) {
            ((AudioSource) sendingHandler).destroy();
            audioManager.setSendingHandler(null);
        }

        interactionHook.editOriginal("Will do!").queue();
    }

    private void onJoinCommand(@NotNull SlashCommandEvent event) {
        //Defer Reply
        event.deferReply().queue();
        //Get InteractionHook to later reply
        InteractionHook interactionHook = event.getHook();
        //The Member who caused the Event
        Member eventMember = event.getMember();

        //First condition is sufficient for eventMember to not be null due to the API contract, but for better IDE integration we check explicitly for null too
        if (!event.isFromGuild() || eventMember == null) {
            interactionHook.editOriginal("This can only be used from inside a Guild!").queue();
            return;
        }

        //Cant be Null when the application is correctly configured
        GuildVoiceState voiceState = Objects.requireNonNull(eventMember.getVoiceState());

        if (!voiceState.inVoiceChannel()) {
            interactionHook.editOriginal("You have to join a Voice-Channel yourself first!").queue();
            return;
        }

        Guild guild = Objects.requireNonNull(event.getGuild());
        AudioManager audioManager = guild.getAudioManager();
        VoiceChannel channel = voiceState.getChannel();

        if (audioManager.getSendingHandler() == null) {
            try {
                audioManager.setSendingHandler(new AudioSource());
            } catch (LineUnavailableException e) {
                this.logger.error("Unable to create AudioSource", e);
                interactionHook.editOriginal("An internal Error occured").queue();
                return;
            }
        }

        audioManager.openAudioConnection(channel);
        interactionHook.editOriginal("Will do!").queue();
    }

    @EventListener
    public void onSongStart(StartSongEvent event) {
        this.JDA.getPresence().setActivity(Activity.listening(event.getSong().getSongtitle()));
    }

    @EventListener
    public void onSongStop(StopSongEvent event) {
        this.JDA.getPresence().setActivity(null);
    }

    @EventListener
    public void onSongFinished(SongFinishedEvent event) {
        this.JDA.getPresence().setActivity(null);
    }
}
