export class Container {
    constructor({containerId, image, command, created, status, ports, names}) {
        Object.assign(this, {containerId, image, command, created, status, ports, names});
    }
}