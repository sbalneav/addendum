# Where "make deploy" installs. Set it on the command line (make deploy SERVER=host)
# or in local.mk, which is git-ignored.
-include local.mk
SERVER ?=

BIN_DIR   = $(HOME)/bin
LIB_DIR   = $(HOME)/.local/share/addendum
FORMS_DIR = $(HOME)/.config/addendum/forms

FORMS = $(notdir $(wildcard server/forms/*))

.PHONY: test install-server uninstall-server deploy

test:
	./server/test.sh

# Run on the server itself.
install-server:
	install -Dm755 server/addendum $(BIN_DIR)/addendum
	install -Dm644 server/lib.sh $(LIB_DIR)/lib.sh
	for f in $(FORMS); do install -Dm755 server/forms/$$f $(FORMS_DIR)/$$f; done

uninstall-server:
	rm -f $(BIN_DIR)/addendum $(LIB_DIR)/lib.sh
	for f in $(FORMS); do rm -f $(FORMS_DIR)/$$f; done

# Run here: test, then copy each piece to $(SERVER), straight into install.
deploy: test
	@test -n "$(SERVER)" || { echo "Set SERVER, e.g. make deploy SERVER=myhost (or put it in local.mk)"; exit 1; }
	ssh $(SERVER) 'install -Dm755 /dev/stdin ~/bin/addendum' < server/addendum
	ssh $(SERVER) 'install -Dm644 /dev/stdin ~/.local/share/addendum/lib.sh' < server/lib.sh
	for f in $(FORMS); do \
	    ssh $(SERVER) "install -Dm755 /dev/stdin ~/.config/addendum/forms/$$f" < server/forms/$$f || exit 1; \
	done
