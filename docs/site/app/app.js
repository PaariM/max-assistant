(() => {
  const status = document.querySelector("#status");
  const messages = document.querySelector("#messages");
  const language = document.querySelector("#language");
  const micButton = document.querySelector("#mic-button");
  const installButton = document.querySelector("#install-button");
  const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
  let deferredInstallPrompt;

  function setStatus(message) {
    status.textContent = message;
  }

  function addMessage(text, fromUser = false) {
    const article = document.createElement("article");
    article.className = `message ${fromUser ? "user-message" : "assistant-message"}`;
    if (!fromUser) {
      const avatar = document.createElement("span");
      avatar.className = "avatar";
      avatar.textContent = "M";
      article.append(avatar);
    }
    const paragraph = document.createElement("p");
    paragraph.textContent = text;
    article.append(paragraph);
    messages.append(article);
    messages.scrollTop = messages.scrollHeight;
  }

  function speak(text) {
    if (!("speechSynthesis" in window)) return;
    window.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = language.value;
    window.speechSynthesis.speak(utterance);
  }

  function reply(text) {
    addMessage(text);
    setStatus(text);
    speak(text);
  }

  function openWhatsApp(text = "") {
    const url = new URL("https://wa.me/");
    if (text) url.searchParams.set("text", text);
    window.location.assign(url.href);
  }

  async function shareText(text) {
    if (navigator.share) {
      try {
        await navigator.share({ text, title: "Message from Max" });
        setStatus("Share sheet opened. Choose an app and confirm.");
      } catch (error) {
        if (error.name !== "AbortError") reply("I couldn’t open the share sheet on this device.");
      }
      return;
    }
    try {
      await navigator.clipboard.writeText(text);
      reply("Copied your text. Open a messaging app and paste it.");
    } catch {
      reply("This browser can’t share or copy text here. Select and copy the message manually.");
    }
  }

  function handleCommand(rawCommand) {
    const command = rawCommand.trim();
    if (!command) return;
    addMessage(command, true);
    const addressedMessage = command
      .replace(/\s+(?:chat|on whatsapp|via whatsapp|in whatsapp)$/i, "")
      .match(/^(?:say|send|text|message)\s+(.+?)\s+to\s+(.+)$/i);
    const openWhatsAppMatch = command.match(/^open whatsapp(?: and (?:type|write|send) (.+))?$/i);
    const whatsAppMessageMatch = command.match(/^(?:send|message) (.+?)\s+(?:on|via|in|to) whatsapp$/i);
    const lower = command.toLocaleLowerCase();

    if (addressedMessage) {
      const text = addressedMessage[1].trim();
      const contact = addressedMessage[2].trim();
      reply(`I can’t access your contacts in a browser. Opening WhatsApp with “${text}” ready; choose ${contact} and review it before sending.`);
      openWhatsApp(text);
    } else if (openWhatsAppMatch) {
      const text = openWhatsAppMatch[1]?.trim() ?? "";
      reply(text
        ? "Opening WhatsApp with your text ready. Choose a chat and review it before sending."
        : "Opening WhatsApp. Choose a chat in WhatsApp.");
      openWhatsApp(text);
    } else if (whatsAppMessageMatch) {
      reply("Opening WhatsApp with your text ready. Choose a chat and review it before sending.");
      openWhatsApp(whatsAppMessageMatch[1].trim());
    } else if (lower === "call" || lower.startsWith("call ")) {
      const requestedNumber = command.slice(4).trim();
      if (!requestedNumber) {
        reply("Say “call” followed by the phone number. I won’t open a text-entry prompt.");
      } else {
        const number = requestedNumber.replace(/[^\d+*#,;]/g, "");
        if (!number) {
          reply("That doesn’t look like a phone number. Try again.");
          return;
        }
        reply("Opening your dialer. Review the number before calling.");
        window.location.assign(`tel:${number}`);
      }
    } else if (lower === "share" || lower.startsWith("share ")) {
      const text = command.slice(5).trim();
      if (text) {
        reply("Preparing the text to share. Choose an app and confirm.");
        void shareText(text);
      } else {
        reply("What text would you like to share?");
      }
    } else if (lower.startsWith("send ") || lower.startsWith("message ")) {
      const text = command.slice(command.indexOf(" ") + 1).trim();
      if (text) {
        reply("Preparing the text to share. Choose a messaging app and confirm.");
        void shareText(text);
      } else {
        reply("What would you like to send?");
      }
    } else if (lower === "help") {
      reply("Try: open WhatsApp, open WhatsApp and type hello, send hello via WhatsApp, call, or share hello.");
    } else {
      reply("I can open WhatsApp, prepare a message to share, or open the dialer. Browsers can’t launch arbitrary apps.");
    }
  }

  function listen() {
    if (!SpeechRecognition) {
      setStatus("Voice recognition isn’t supported by this browser. Try a supported browser or the Android app.");
      return;
    }
    const recognition = new SpeechRecognition();
    recognition.lang = language.value;
    recognition.interimResults = false;
    recognition.maxAlternatives = 1;
    micButton.disabled = true;
    micButton.classList.add("listening");
    setStatus("Listening…");
    recognition.onresult = event => {
      const transcript = event.results?.[0]?.[0]?.transcript?.trim();
      if (transcript) {
        handleCommand(transcript);
      } else {
        setStatus("I didn’t catch that. Tap the microphone and try again.");
      }
    };
    recognition.onerror = event => {
      setStatus(event.error === "not-allowed"
        ? "Microphone permission was denied. Allow it in your browser settings."
        : "Voice recognition stopped. Try again or type your request.");
    };
    recognition.onend = () => {
      micButton.disabled = false;
      micButton.classList.remove("listening");
    };
    try {
      recognition.start();
    } catch {
      micButton.disabled = false;
      micButton.classList.remove("listening");
      setStatus("Couldn’t start the microphone. Try again or type your request.");
    }
  }

  micButton.addEventListener("click", listen);
  document.querySelector("#clear-button").addEventListener("click", () => {
    messages.replaceChildren();
    setStatus("Conversation cleared.");
  });
  document.querySelectorAll("[data-command]").forEach(button => {
    button.addEventListener("click", () => {
      const command = button.dataset.command;
      if (command === "Call") reply("Say “call” followed by the phone number.");
      else if (command === "Share") handleCommand("Share");
      else handleCommand(command);
    });
  });

  window.addEventListener("beforeinstallprompt", event => {
    event.preventDefault();
    deferredInstallPrompt = event;
    installButton.hidden = false;
  });
  installButton.addEventListener("click", async () => {
    if (deferredInstallPrompt) {
      await deferredInstallPrompt.prompt();
      deferredInstallPrompt = null;
      installButton.hidden = true;
    } else {
      setStatus("In Safari, tap Share, then Add to Home Screen.");
    }
  });
  window.addEventListener("appinstalled", () => {
    installButton.hidden = true;
    setStatus("Max was added to your home screen.");
  });

  if (/iphone|ipad|ipod/i.test(navigator.userAgent) && !window.navigator.standalone) {
    installButton.hidden = false;
  }
})();
