#include "strings.h"

#include "common.h"

static const wchar_t* kEn[S_COUNT] = {
    L"Shared with %s",
    L"Stop",
    L"Ready — waiting for your phone",
    L"Connected: %s",
    L"Pairing open",
    L"Pair a phone…",
    L"Linked phones",
    L"(none)",
    L"Unlink “%s”?\n\nIt will no longer be able to control this PC.",
    L"Send a file to the phone…",
    L"Disconnect the phone",
    L"Open received files",
    L"Start with Windows",
    L"Install as a service (lock screen + admin prompts)…",
    L"Exit",
    L"DeskLink — Pair a phone",
    L"Pair a phone",
    L"1.  Install DeskLink on your phone and open it.\n2.  Tap “Add PC” and choose “%s”.\n3.  Check that both codes match, then click Allow.",
    L"Waiting for a phone…  %s left",
    L"Pairing is closed. Open it again to link a new phone.",
    L"“%s” wants to control this PC",
    L"Allow it only if your phone shows this same code:",
    L"Device: %s",
    L"Allow",
    L"Deny",
    L"Close",
    L"Open pairing",
    L"Linked with “%s”",
    L"You can now control this PC from that phone — at home or away.",
    L"Pairing not completed",
    L"The request from “%s” was declined or timed out.",
    L"Finishing…",
    L"File received",
    L"This PC: %s",
    L"Tip: install DeskLink as a service so it starts with Windows and also works on the lock screen and admin prompts.",
    L"Install",
    L"DeskLink is installed and will start with Windows.",
    L"Allow access over mobile data",
    L"Mobile data: off",
    L"Mobile data: checking the router…",
    L"Mobile data: ready (%s)",
    L"Mobile data: only over IPv6, if the router lets it in",
    L"Mobile data: not possible, your internet company shares your address (CGNAT)",
    L"Mobile data: the router didn't let DeskLink open the way in (UPnP)",
};

static const wchar_t* kEs[S_COUNT] = {
    L"Compartido con %s",
    L"Detener",
    L"Listo — esperando tu teléfono",
    L"Conectado: %s",
    L"Vinculación abierta",
    L"Vincular un teléfono…",
    L"Teléfonos vinculados",
    L"(ninguno)",
    L"¿Desvincular “%s”?\n\nYa no podrá controlar esta PC.",
    L"Enviar un archivo al teléfono…",
    L"Desconectar el teléfono",
    L"Abrir archivos recibidos",
    L"Iniciar con Windows",
    L"Instalar como servicio (bloqueo y avisos de administrador)…",
    L"Salir",
    L"DeskLink — Vincular un teléfono",
    L"Vincular un teléfono",
    L"1.  Instala DeskLink en tu teléfono y ábrelo.\n2.  Toca “Agregar PC” y elige “%s”.\n3.  Comprueba que ambos códigos coincidan y haz clic en Permitir.",
    L"Esperando un teléfono…  quedan %s",
    L"La vinculación está cerrada. Ábrela de nuevo para vincular otro teléfono.",
    L"“%s” quiere controlar esta PC",
    L"Permítelo solo si tu teléfono muestra este mismo código:",
    L"Dispositivo: %s",
    L"Permitir",
    L"Rechazar",
    L"Cerrar",
    L"Abrir vinculación",
    L"Vinculado con “%s”",
    L"Ya puedes controlar esta PC desde ese teléfono, en casa o fuera.",
    L"No se completó la vinculación",
    L"La solicitud de “%s” se rechazó o venció.",
    L"Terminando…",
    L"Archivo recibido",
    L"Esta PC: %s",
    L"Consejo: instala DeskLink como servicio para que inicie con Windows y funcione también en la pantalla de bloqueo y los avisos de administrador.",
    L"Instalar",
    L"DeskLink quedó instalado y se iniciará con Windows.",
    L"Permitir acceso con datos móviles",
    L"Datos móviles: desactivado",
    L"Datos móviles: revisando el router…",
    L"Datos móviles: listo (%s)",
    L"Datos móviles: solo por IPv6, si el router lo deja entrar",
    L"Datos móviles: no se puede, tu proveedor de internet comparte tu dirección (CGNAT)",
    L"Datos móviles: el router no dejó que DeskLink abra el acceso (UPnP)",
};

const wchar_t* S(StrId id) {
  static const bool es = IsSpanishUi();
  if (id < 0 || id >= S_COUNT) return L"";
  return es ? kEs[id] : kEn[id];
}

std::wstring Fmt(StrId id, const std::wstring& a) {
  std::wstring f = S(id);
  size_t k = f.find(L"%s");
  if (k != std::wstring::npos) f.replace(k, 2, a);
  return f;
}
